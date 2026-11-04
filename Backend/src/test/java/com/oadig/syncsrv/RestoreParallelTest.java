package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.*;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreParallelTest {
  @TempDir Path root;
  @Test void reconstructsFromBAndCConcurrentlyAndReceiptWaitsForAllFiles() throws Exception {
    var memory=new MemoryStore();var config=mock(ConfigurationService.class);
    Job job=new Job();job.id="job";job.sourceId="A";job.targetId="B";job.archiveId="C";job.archiveThreads=4;
    for(String id:List.of("A","B","C")) {
      Endpoint e=new Endpoint();e.id=id;e.root=Files.createDirectory(root.resolve(id)).toString();
      when(config.endpoint(id)).thenReturn(e);
    }
    when(config.job("job")).thenReturn(job);
    var transfer=new TransferService(memory.store);var engine=new SyncEngine(memory.store,config,transfer);
    for(int i=0;i<4;i++)Files.writeString(root.resolve("A/file"+i),"old "+i);
    engine.sync(job,CenterParallelTest.task());var snapshot=engine.snapshots().get(0);
    Files.writeString(root.resolve("A/file0"),"updated");
    engine.sync(job,CenterParallelTest.task());
    CountDownLatch entered=new CountDownLatch(4), release=new CountDownLatch(1);
    AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger();
    Set<String> reading=ConcurrentHashMap.newKeySet();
    var restore=new RestoreService(memory.store,config,engine,transfer,endpoint->{
      opened.incrementAndGet();
      return new TransferTest.Delegate(new LocalStorage(Path.of(endpoint.root))) {
        public InputStream read(String path,long offset) throws Exception {
          reading.add(endpoint.id);entered.countDown();
          if(!release.await(10,TimeUnit.SECONDS))throw new IOException("no release");
          return super.read(path,offset);
        }
        public void close() {closed.incrementAndGet();super.close();}
      };
    });
    var task=CenterParallelTest.task();Path d=root.resolve("D");
    var executor=Executors.newSingleThreadExecutor();
    Future<?> run=executor.submit(()->{restore.restore(snapshot.id(),d.toString(),task);return null;});
    try {
      assertTrue(entered.await(10,TimeUnit.SECONDS),"B and C restore reads must overlap");
      assertEquals(Set.of("B","C"),reading);assertEquals(4,task.progress.snapshot(task.run).activeFiles());
      assertFalse(Files.exists(d.resolve(".syncsrv/receipt.json")));
      assertFalse(memory.actions.contains("restore.completed"));
      release.countDown();run.get(20,TimeUnit.SECONDS);
      for(int i=0;i<4;i++)assertEquals("old "+i,Files.readString(d.resolve("file"+i)));
      assertEquals("updated",Files.readString(root.resolve("B/file0")));
      assertEquals(opened.get(),closed.get());assertEquals(4,task.run.done);
      assertEquals(4,task.progress.snapshot(task.run).peakActiveFiles());
      assertTrue(Files.exists(d.resolve(".syncsrv/receipt.json")));
      assertNotNull(memory.store.get("receipt",task.run.id,RestoreService.Receipt.class));
      task.metrics.finish();assertEquals(4,task.metrics.snapshot().slowestOperations().size());
    } finally {release.countDown();executor.shutdownNow();executor.awaitTermination(20,TimeUnit.SECONDS);}
  }

  @Test void parallelFailureNeverPublishesReceiptAndVerifiedFilesCanResume() throws Exception {
    var memory=new MemoryStore();var config=mock(ConfigurationService.class);
    Job job=new Job();job.id="job";job.sourceId="A";job.targetId="B";job.archiveId="C";job.archiveThreads=4;
    for(String id:List.of("A","B","C")) {
      Endpoint e=new Endpoint();e.id=id;e.root=Files.createDirectory(root.resolve(id)).toString();
      when(config.endpoint(id)).thenReturn(e);
    }
    when(config.job("job")).thenReturn(job);
    var transfer=new TransferService(memory.store);var engine=new SyncEngine(memory.store,config,transfer);
    for(int i=0;i<4;i++)Files.writeString(root.resolve("A/file"+i),"old "+i);
    engine.sync(job,CenterParallelTest.task());var snapshot=engine.snapshots().get(0);
    Files.writeString(root.resolve("A/file0"),"new version");engine.sync(job,CenterParallelTest.task());
    Map<String,String> beforeB=contents(root.resolve("B")),beforeC=contents(root.resolve("C"));
    CountDownLatch reading=new CountDownLatch(4);
    AtomicBoolean failOnce=new AtomicBoolean(true);
    AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger();
    var restore=new RestoreService(memory.store,config,engine,transfer,endpoint->{
      opened.incrementAndGet();
      return new TransferTest.Delegate(new LocalStorage(Path.of(endpoint.root))) {
        public InputStream read(String path,long offset) throws Exception {
          reading.countDown();
          if(!reading.await(10,TimeUnit.SECONDS))throw new IOException("parallel readers did not start");
          if(path.equals("file1") && failOnce.compareAndSet(true,false))throw new IOException("injected source read failure");
          return super.read(path,offset);
        }
        public void close() {closed.incrementAndGet();super.close();}
      };
    });
    Path destination=root.resolve("D");var task=CenterParallelTest.task();
    var executor=Executors.newSingleThreadExecutor();
    try {
      Future<?> failed=executor.submit(()->{restore.restore(snapshot.id(),destination.toString(),task);return null;});
      ExecutionException error=assertThrows(ExecutionException.class,()->failed.get(20,TimeUnit.SECONDS));
      assertTrue(error.getCause().getMessage().contains("injected source read failure"));
      assertEquals(4,opened.get());assertEquals(opened.get(),closed.get());
      assertEquals(0,task.progress.snapshot(task.run).activeFiles(),"failure must drain every writer");
      assertEquals(3,task.run.done,"other in-flight verified files may finish and remain resumable");
      assertFalse(Files.exists(destination.resolve(".syncsrv/receipt.json")));
      assertFalse(memory.actions.contains("restore.completed"));
      assertNull(memory.store.get("receipt",task.run.id,RestoreService.Receipt.class));
      assertTrue(Files.exists(destination.resolve(".syncsrv/restore-owner")));
      assertEquals(beforeB,contents(root.resolve("B")));assertEquals(beforeC,contents(root.resolve("C")));
      var retry=CenterParallelTest.task();
      new RestoreService(memory.store,config,engine,transfer).restore(snapshot.id(),destination.toString(),retry);
      for(int i=0;i<4;i++)assertEquals("old "+i,Files.readString(destination.resolve("file"+i)));
      assertEquals(4,retry.run.done);assertTrue(Files.exists(destination.resolve(".syncsrv/receipt.json")));
      assertNotNull(memory.store.get("receipt",retry.run.id,RestoreService.Receipt.class));
      assertEquals(beforeB,contents(root.resolve("B")));assertEquals(beforeC,contents(root.resolve("C")));
    } finally {executor.shutdownNow();executor.awaitTermination(20,TimeUnit.SECONDS);}
  }

  private static Map<String,String> contents(Path directory) throws IOException {
    Map<String,String> result=new TreeMap<>();
    try(var paths=Files.walk(directory)) {
      for(Path path:paths.filter(Files::isRegularFile).toList())
        result.put(directory.relativize(path).toString(),com.oadig.syncsrv.shared.Ids.hash(Files.readAllBytes(path)));
    }
    return result;
  }

}
