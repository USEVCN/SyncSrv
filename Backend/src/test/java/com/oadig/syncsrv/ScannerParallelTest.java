package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import com.oadig.syncsrv.modules.sync.TreeScanner;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.Node;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScannerParallelTest {
  @TempDir Path root;
  @Test void realHashReadsOverlapWithPrivateSessionsAndNoExtraBackupEntries() throws Exception {
    for (int i=0;i<8;i++) Files.writeString(root.resolve("file"+i),"content "+i);
    var scanner=new TreeScanner(new TransferService(new MemoryStore().store));
    var task=CenterParallelTest.task(); task.progress.concurrency(4);
    CountDownLatch reading=new CountDownLatch(4), release=new CountDownLatch(1);
    AtomicInteger opened=new AtomicInteger(), closed=new AtomicInteger();
    var executor=Executors.newSingleThreadExecutor();
    try (Storage metadata=new LocalStorage(root)) {
      Future<Map<String,Node>> run=executor.submit(()->scanner.scan(metadata,task,false,true,4,()-> {
        opened.incrementAndGet();
        return new TransferTest.Delegate(new LocalStorage(root)) {
          private Thread owner;
          public InputStream read(String path,long offset) throws Exception {
            if(owner==null)owner=Thread.currentThread();
            assertSame(owner,Thread.currentThread(),"a protocol session must belong to one worker");
            reading.countDown();
            if(!release.await(10,TimeUnit.SECONDS))throw new IOException("no release");
            return super.read(path,offset);
          }
          public void close() { closed.incrementAndGet();super.close(); }
        };
      }));
      try {
        assertTrue(reading.await(10,TimeUnit.SECONDS),"four actual hash streams must overlap");
        assertEquals(4,task.progress.snapshot(task.run).activeChecks());
        assertFalse(run.isDone()); release.countDown();
        var result=run.get(15,TimeUnit.SECONDS);
        for(int i=0;i<8;i++)assertEquals(Ids.textHash("content "+i),result.get("file"+i).hash());
        assertEquals(4,opened.get());assertEquals(opened.get(),closed.get());
        var progress=task.progress.snapshot(task.run);
        assertEquals(0,progress.activeChecks());assertEquals(4,progress.peakChecks());
        assertEquals(0,progress.entries(),"validation must not duplicate backup file totals");
        assertEquals(72,task.run.verifiedBytes);
      } finally { release.countDown(); }
    } finally {executor.shutdownNow();executor.awaitTermination(15,TimeUnit.SECONDS);}
  }

  @Test void mutationBetweenEnumerationAndHashPreventsPublishingScan() throws Exception {
    Files.writeString(root.resolve("file"),"old");
    var scanner=new TreeScanner(new TransferService(new MemoryStore().store));
    var task=CenterParallelTest.task();
    try(Storage metadata=new LocalStorage(root)) {
      Exception error=assertThrows(Exception.class,()->scanner.scan(metadata,task,false,true,4,()->{
        Files.writeString(root.resolve("file"),"changed length");return new LocalStorage(root);
      }));
      assertTrue(error.getMessage().contains("扫描期间文件变化"));
      assertEquals(0,task.progress.snapshot(task.run).activeChecks());
    }
  }

  @Test void nestedVerificationRestoresOriginalFileAndByteProgress() throws Exception {
    var task=CenterParallelTest.task();task.stage("ARCHIVING","original");
    try(var outer=task.verifying("HASHING","file",100)) {
      task.verified(30);
      try(var inner=task.verifying("VERIFYING_RESUME","part",50)) {task.verified(10);}
      assertEquals("HASHING",task.phase());assertEquals("file",task.run.currentPath);
      assertEquals(30,task.run.phaseBytes);assertEquals(100,task.run.phaseTotal);
      task.verified(5);assertEquals(35,task.run.phaseBytes);
    }
    assertEquals("ARCHIVING",task.phase());assertEquals("original",task.run.currentPath);
    assertEquals(0,task.run.phaseTotal);assertEquals(45,task.run.verifiedBytes);
  }
}
