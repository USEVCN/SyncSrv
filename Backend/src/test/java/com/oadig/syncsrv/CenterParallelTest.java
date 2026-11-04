package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.sync.*;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CenterParallelTest {
  @TempDir Path root;

  @Test
  void archivesOverlapButParentsAndSnapshotWaitAndDirectoryTotalsRemainExact() throws Exception {
    var memory = new MemoryStore();
    var config = mock(ConfigurationService.class);
    Job job = new Job(); job.id="job"; job.sourceId="A"; job.targetId="B"; job.archiveId="C";
    job.archiveThreads=4;
    for (String id : List.of("A","B","C")) {
      Endpoint endpoint = new Endpoint(); endpoint.id=id;
      endpoint.root=Files.createDirectory(root.resolve(id)).toString();
      when(config.endpoint(id)).thenReturn(endpoint);
    }
    AtomicBoolean block = new AtomicBoolean();
    CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
    AtomicInteger archives = new AtomicInteger(), inflight = new AtomicInteger(), peak = new AtomicInteger();
    var engine = new SyncEngine(memory.store, config, new TransferService(memory.store), endpoint -> {
      Storage storage = new LocalStorage(Path.of(endpoint.root));
      return new TransferTest.Delegate(storage) {
        public void write(String path, long offset, InputStream input, long size) throws Exception {
          if (block.get() && endpoint.id.equals("C") && path.contains("/versions/")) {
            int active = inflight.incrementAndGet(); peak.accumulateAndGet(active, Math::max);
            entered.countDown();
            try {
              if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("workers never released");
              super.write(path,offset,input,size); archives.incrementAndGet();
            } finally { inflight.decrementAndGet(); }
          } else super.write(path,offset,input,size);
        }
        public void delete(String path, boolean directory) throws Exception {
          if (block.get() && endpoint.id.equals("B") && directory) {
            assertEquals(8,archives.get(),"parent deletion must follow all children");
            assertEquals(0,inflight.get());
          }
          super.delete(path,directory);
        }
      };
    });
    Files.createDirectories(root.resolve("A/folder/nested"));
    for (int i=0;i<8;i++) Files.writeString(root.resolve("A/folder/nested/file"+i),"version "+i);
    TaskContext first=task(); engine.sync(job,first);
    var totals = new DirectoryIndex(memory.store).list(job.id).stream().filter(s->s.path().isEmpty()).findFirst().orElseThrow();
    assertEquals(8,totals.files()); assertEquals(2,totals.directories());
    for (int i=0;i<8;i++) Files.delete(root.resolve("A/folder/nested/file"+i));
    Files.delete(root.resolve("A/folder/nested")); Files.delete(root.resolve("A/folder"));
    block.set(true);
    TaskContext task=task();
    ExecutorService executor=Executors.newSingleThreadExecutor();
    Future<?> run=executor.submit(()->{engine.sync(job,task);return null;});
    try {
      assertTrue(entered.await(10,TimeUnit.SECONDS),"four actual archive writes must overlap");
      assertEquals(4,task.progress.snapshot(task.run).activeFiles());
      assertEquals(4,task.progress.page(0,task.run).entries().stream().filter(r->r.status().equals("RUNNING")).count());
      assertEquals(1,engine.snapshots().size(),"no partial snapshot may be committed");
      assertTrue(Files.exists(root.resolve("B/folder/nested/file0")));
      release.countDown(); run.get(20,TimeUnit.SECONDS);
      task.metrics.finish();
      assertEquals(4,peak.get()); assertEquals(4,task.progress.snapshot(task.run).peakActiveFiles());
      assertTrue(engine.current(job.id).isEmpty()); assertFalse(Files.exists(root.resolve("B/folder")));
      assertEquals(2,engine.snapshots().size()); assertTrue(memory.store.list("operation",Operation.class).isEmpty());
      assertEquals(8,memory.store.list("version",Version.class).stream().filter(v->v.reason().equals("deleted")).count());
      var empty = new DirectoryIndex(memory.store).list(job.id).stream().filter(s->s.path().isEmpty()).findFirst().orElseThrow();
      assertEquals(0,empty.files()); assertEquals(0,empty.logicalBytes()); assertEquals(0,empty.directories());
      var measured=task.metrics.snapshot().slowestOperations().stream().filter(o->o.kind().equals("DELETE")).toList();
      assertEquals(8,measured.size());
      assertTrue(measured.stream().allMatch(o->o.writtenBytes()==9 && o.transferBytes()==9),"each file owns only its I/O");
    } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(20,TimeUnit.SECONDS); }
  }

  static TaskContext task() {
    Run run=new Run(); run.id=Ids.next();run.actor="test";run.status="RUNNING";
    var task=new TaskContext(run,new BandwidthLimiter(0),0);
    task.metrics.start();task.progress.start();return task;
  }
}
