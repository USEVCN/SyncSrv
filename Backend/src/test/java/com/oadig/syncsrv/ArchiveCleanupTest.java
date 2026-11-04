package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.archive.*;
import com.oadig.syncsrv.modules.archive.ArchiveModels.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.*;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveCleanupTest {
  @TempDir Path root;

  final class Fixture {
    final MemoryStore memory = new MemoryStore();
    final ConfigurationService config = mock(ConfigurationService.class);
    final Job job = new Job();
    final SyncEngine sync;
    final TransferService transfer;
    final ArchiveService archive;
    final Path a, b, c;
    final Snapshot oldest, baseline, latest;
    final String month = YearMonth.now(ZoneId.of("Asia/Shanghai")).minusMonths(1).toString();
    Fixture() throws Exception {
      job.id = "job"; job.sourceId = "A"; job.targetId = "B"; job.archiveId = "C"; job.archiveThreads = 2;
      for (String id : List.of("A", "B", "C")) {
        Endpoint endpoint = new Endpoint(); endpoint.id = id;
        endpoint.root = Files.createDirectory(root.resolve(id)).toString();
        when(config.endpoint(id)).thenReturn(endpoint);
        memory.put("endpoint", id, endpoint);
      }
      a=root.resolve("A"); b=root.resolve("B"); c=root.resolve("C");
      when(config.job(job.id)).thenReturn(job); when(config.jobs()).thenReturn(List.of(job));
      memory.put("job",job.id,job);
      transfer=new TransferService(memory.store); sync=new SyncEngine(memory.store,config,transfer);
      Files.writeString(a.resolve("f.txt"),"old"); Files.writeString(a.resolve("shared.txt"),"shared");
      sync.sync(job,task()); oldest=retime(sync.snapshots().get(0),-3);
      Files.writeString(a.resolve("f.txt"),"boundary version");
      sync.sync(job,task()); baseline=retime(sync.snapshots().get(0),-2);
      Files.writeString(a.resolve("f.txt"),"latest version");
      sync.sync(job,task()); latest=sync.snapshots().get(0);
      Files.writeString(c.resolve("unknown.txt"),"leave me alone");
      archive=new ArchiveService(memory.store,config);
    }
    Snapshot retime(Snapshot s,int months) throws Exception {
      String time=YearMonth.now(ZoneId.of("Asia/Shanghai")).plusMonths(months).atDay(10)
          .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toString();
      Snapshot changed=new Snapshot(s.id(),s.jobId(),s.sequence(),time,time,s.entries(),s.manifestHash(),s.totalBytes());
      memory.put("snapshot",changed.id(),changed);
      memory.put("snapshot-summary",changed.id(),SnapshotSummary.from(changed));
      Files.write(c.resolve(job.id+"/snapshots/"+changed.id()+".json"),Json.bytes(changed));
      return changed;
    }
    Plan preview() throws Exception { return archive.preview(job.id,new Request(month,List.of()),"test"); }
    TaskContext cleanup(Plan plan) {
      var t=task();t.run.kind="ARCHIVE_CLEANUP";t.run.jobId=job.id;t.run.target=plan.id;
      memory.put("run",t.run.id,t.run);return t;
    }
    Path file(Item item) { return c.resolve(item.location()); }
    Item version(Plan p) { return p.items.stream().filter(i->i.kind().equals("VERSION")).findFirst().orElseThrow(); }
  }

  static TaskContext task() {
    Run r=new Run();r.id=Ids.next();r.actor="test";r.status="RUNNING";r.startedAt=Ids.now();
    TaskContext task=new TaskContext(r,new BandwidthLimiter(0),0);task.metrics.start();task.progress.start();return task;
  }

  @Test void removesOnlyUnreferencedHistoryAndRetainedPointStillReconstructsFromBAndC() throws Exception {
    var f=new Fixture();Map<String,String> a=contents(f.a),b=contents(f.b),c=contents(f.c);
    Plan plan=f.preview();assertEquals(c,contents(f.c),"preview must never delete");
    assertEquals(List.of(f.oldest.id()),plan.expired.stream().map(SnapshotSummary::id).toList());
    assertEquals(2,plan.retained.size());assertEquals(2,plan.items.size());
    var restore=new RestoreService(f.memory.store,f.config,f.sync,f.transfer);
    var before=task();restore.restore(f.baseline.id(),root.resolve("before").toString(),before);
    before.run.kind="RESTORE";before.run.jobId=f.job.id;before.run.snapshotId=f.baseline.id();before.run.status="COMPLETED";
    f.memory.put("run",before.run.id,before.run);
    // The completed restore receipt must not change the live reference fingerprint.
    plan=f.preview();var t=f.cleanup(plan);f.archive.execute(f.job.id,plan.id,t);
    Plan result=f.archive.get(f.job.id,plan.id);
    assertEquals("COMPLETED",result.state);assertEquals(plan.estimatedBytes,result.deletedBytes);
    assertEquals(plan.items.size(),result.completedFiles);assertEquals(0,result.missingFiles);
    for(Item item:plan.items)assertFalse(Files.exists(f.file(item)));
    assertEquals("leave me alone",Files.readString(f.c.resolve("unknown.txt")));
    assertEquals(a,contents(f.a));assertEquals(b,contents(f.b));
    assertThrows(Exception.class,()->f.sync.snapshot(f.oldest.id()));
    assertNotNull(f.memory.store.get("retired-snapshot",f.oldest.id(),ArchiveService.RetiredSnapshot.class));
    restore.restore(f.baseline.id(),root.resolve("after").toString(),task());
    assertEquals("boundary version",Files.readString(root.resolve("after/f.txt")));
    assertEquals("shared",Files.readString(root.resolve("after/shared.txt")));
    assertEquals(contents(root.resolve("before")).get("f.txt"),contents(root.resolve("after")).get("f.txt"));
    assertTrue(f.memory.actions.indexOf("archive.snapshots_retired") < f.memory.actions.indexOf("archive.remove_intent"));
    assertTrue(f.memory.actions.contains("archive.file_removed"));assertTrue(f.memory.actions.contains("archive.completed"));
    assertEquals(plan.items.size(),t.metrics.snapshot().cleanupFiles());
  }

  @Test void newSnapshotInvalidatesPreviewBeforeAnyRetirementOrDeletion() throws Exception {
    var f=new Fixture();Plan plan=f.preview();Map<String,String> c=contents(f.c);
    Files.writeString(f.a.resolve("later.txt"),"later");f.sync.sync(f.job,task());
    Map<String,String> afterBackup=contents(f.c);
    Exception error=assertThrows(Exception.class,()->f.archive.execute(f.job.id,plan.id,f.cleanup(plan)));
    assertTrue(error.getMessage().contains("重新预览"));assertEquals(afterBackup,contents(f.c));
    assertNotNull(f.sync.snapshot(f.oldest.id()));assertFalse(f.memory.actions.contains("archive.remove_intent"));
    assertTrue(afterBackup.keySet().containsAll(c.keySet()));
  }

  @Test void deleteSucceededButReplyFailedResumesWithoutDoubleCountingSpace() throws Exception {
    var f=new Fixture();f.job.archiveThreads=1;Plan plan=f.preview();AtomicBoolean once=new AtomicBoolean(true);
    var failing=new ArchiveService(f.memory.store,f.config,e->new TransferTest.Delegate(new LocalStorage(Path.of(e.root))){
      public void delete(String path,boolean directory) throws Exception {
        super.delete(path,directory);if(once.compareAndSet(true,false))throw new IOException("reply lost after delete");
      }
    });
    assertThrows(Exception.class,()->failing.execute(f.job.id,plan.id,f.cleanup(plan)));
    long remaining=plan.items.stream().filter(i->Files.exists(f.file(i))).mapToLong(Item::size).sum();
    assertTrue(remaining<plan.estimatedBytes);
    f.archive.execute(f.job.id,plan.id,f.cleanup(plan));
    Plan result=f.archive.get(f.job.id,plan.id);assertEquals("COMPLETED",result.state);
    assertEquals(1,result.missingFiles);assertEquals(remaining,result.deletedBytes);
    assertEquals(plan.items.size(),result.completedFiles);
    assertTrue(f.memory.actions.contains("archive.file_absent_after_intent"));
  }

  @Test void auditIntentFailurePreventsPhysicalDeletion() throws Exception {
    var f=new Fixture();f.job.archiveThreads=1;Plan plan=f.preview();Map<String,String> c=contents(f.c);
    doThrow(new IOException("audit unavailable")).when(f.memory.store).commit(anyString(),eq("archive.remove_intent"),
        anyString(),any(),any(Store.Change[].class));
    assertThrows(Exception.class,()->f.archive.execute(f.job.id,plan.id,f.cleanup(plan)));
    assertEquals(c,contents(f.c));assertFalse(f.memory.actions.contains("archive.file_removed"));
  }

  @Test void changedContentAndUnknownFilesAreNeverDeleted() throws Exception {
    var f=new Fixture();Plan plan=f.preview();Item old=f.version(plan);
    Files.writeString(f.file(old),"BAD");
    assertThrows(Exception.class,()->f.archive.execute(f.job.id,plan.id,f.cleanup(plan)));
    assertEquals("BAD",Files.readString(f.file(old)));
    assertEquals("leave me alone",Files.readString(f.c.resolve("unknown.txt")));
    assertNotNull(f.memory.store.get("version",old.versionIds().get(0),Version.class));
  }

  @Test void symbolicParentCannotEscapeArchiveRoot() throws Exception {
    var f=new Fixture();Plan plan=f.preview();Item old=f.version(plan);
    Path parent=f.file(old).getParent(),outside=root.resolve("outside");
    Files.move(parent,outside);Files.createSymbolicLink(parent,outside);
    assertThrows(Exception.class,()->f.archive.execute(f.job.id,plan.id,f.cleanup(plan)));
    assertTrue(Files.exists(outside.resolve(f.file(old).getFileName())));
  }

  @Test void interruptedPlanRechecksNewReferencesAndPreservesNewlyProtectedVersion() throws Exception {
    var f=new Fixture();f.job.archiveThreads=1;Plan plan=f.preview();Item old=f.version(plan);
    var failing=new ArchiveService(f.memory.store,f.config,e->new TransferTest.Delegate(new LocalStorage(Path.of(e.root))){
      public void delete(String path,boolean directory) throws Exception {
        if(path.equals(old.location()))throw new IOException("temporary failure");super.delete(path,directory);
      }
    });
    assertThrows(Exception.class,()->failing.execute(f.job.id,plan.id,f.cleanup(plan)));
    Operation op=new Operation();op.id=Ids.next();op.jobId=f.job.id;op.kind="DELETE";
    op.oldVersion=f.memory.store.get("version",old.versionIds().get(0),Version.class);
    f.memory.put("operation",op.id,op);
    f.archive.execute(f.job.id,plan.id,f.cleanup(plan));
    Plan result=f.archive.get(f.job.id,plan.id);
    assertEquals("COMPLETED",result.state);assertEquals(1,result.skippedFiles);
    assertTrue(Files.exists(f.file(old)));assertTrue(f.memory.actions.contains("archive.file_protected"));
    assertEquals(plan.items.size()-1,result.completedFiles);
  }

  @Test void deletionWorkersOverlapAndCloseBeforeCompletion() throws Exception {
    var f=new Fixture();Plan plan=f.preview();CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
    AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger();
    var concurrent=new ArchiveService(f.memory.store,f.config,e->{opened.incrementAndGet();
      return new TransferTest.Delegate(new LocalStorage(Path.of(e.root))){
        public void delete(String path,boolean directory) throws Exception {
          entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IOException("workers did not overlap");
          super.delete(path,directory);
        }
        public void close(){closed.incrementAndGet();super.close();}
      };
    });
    var executor=Executors.newSingleThreadExecutor();var t=f.cleanup(plan);
    try {
      Future<?> future=executor.submit(()->{concurrent.execute(f.job.id,plan.id,t);return null;});
      assertTrue(entered.await(10,TimeUnit.SECONDS));assertFalse(f.memory.actions.contains("archive.completed"));
      release.countDown();future.get(15,TimeUnit.SECONDS);
      assertEquals(2,opened.get());assertEquals(opened.get(),closed.get());assertEquals(2,t.progress.snapshot(t.run).peakActiveFiles());
    } finally {release.countDown();executor.shutdownNow();executor.awaitTermination(15,TimeUnit.SECONDS);}
  }

  @Test void explicitConfirmationIsRequiredAndQueueExcludesConcurrentBackup() throws Exception {
    var f=new Fixture();Plan plan=f.preview();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    SyncEngine blocked=mock(SyncEngine.class);
    doAnswer(i->{entered.countDown();release.await(10,TimeUnit.SECONDS);return null;}).when(blocked)
        .sync(eq(f.job),any(TaskContext.class));
    try(TaskManager tasks=new TaskManager(f.memory.store,f.config,blocked,mock(RestoreService.class))){
      assertThrows(Exception.class,()->tasks.archive(f.job.id,plan.id,false,"test"));
      tasks.submit(f.job.id,"test");assertTrue(entered.await(3,TimeUnit.SECONDS));
      assertThrows(Exception.class,()->tasks.archive(f.job.id,plan.id,true,"test"));
      assertTrue(Files.exists(f.file(f.version(plan))));release.countDown();
      long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(tasks.busy()&&System.nanoTime()<until)Thread.sleep(10);
      Run run=tasks.archive(f.job.id,plan.id,true,"test");
      until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(tasks.busy()&&System.nanoTime()<until)Thread.sleep(10);
      assertFalse(tasks.busy());assertEquals("COMPLETED",tasks.report(run.id).run().status);
      assertEquals("ARCHIVE_CLEANUP",tasks.report(run.id).run().kind);
    } finally {release.countDown();}
  }

  static Map<String,String> contents(Path directory) throws Exception {
    Map<String,String> result=new TreeMap<>();
    try(var paths=Files.walk(directory)){for(Path path:paths.filter(Files::isRegularFile).toList())
      result.put(directory.relativize(path).toString(),Ids.hash(Files.readAllBytes(path)));}
    return result;
  }
}
