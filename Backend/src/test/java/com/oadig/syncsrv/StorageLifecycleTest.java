package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.storage.LocalStorage;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageLifecycleTest {
  @TempDir Path root;
  @Test void closingAnInterruptedSessionReleasesOpenStreamsAndTheRoot() throws Exception {
    Path folder=Files.createDirectory(root.resolve("source"));Files.writeString(folder.resolve("file"),"content");
    var storage=new LocalStorage(folder);var stream=storage.read("file",0);
    Thread.currentThread().interrupt();
    try { storage.close(); assertTrue(Thread.currentThread().isInterrupted()); }
    finally { Thread.interrupted(); }
    assertThrows(java.io.IOException.class,stream::read);
    Files.delete(folder.resolve("file"));Files.delete(folder);
  }
  @Test void directoryEnumerationAndStatUseZeroLogicalBytes() throws Exception {
    Files.createDirectories(root.resolve("中文/nested"));Files.writeString(root.resolve("中文/file"),"x");
    try(var storage=new LocalStorage(root)) {
      assertEquals(0,storage.stat("中文").size());
      assertEquals(0,storage.list("中文").stream().filter(i->i.directory()).findFirst().orElseThrow().size());
    }
  }
  @Test void completedSyncLeavesNoNativeHelpersOrLockedRoots() throws Exception {
    var before=helpers();var memory=new MemoryStore();var config=mock(ConfigurationService.class);
    var job=new Job();job.id="job";job.sourceId="source";job.targetId="backup";job.archiveId="archive";
    for(String id:List.of(job.sourceId,job.targetId,job.archiveId)) {
      var endpoint=new Endpoint();endpoint.id=id;endpoint.root=Files.createDirectory(root.resolve(id)).toString();
      when(config.endpoint(id)).thenReturn(endpoint);
    }
    Files.createDirectories(root.resolve("source/中文/nested"));
    Files.writeString(root.resolve("source/中文/nested/file"),"contents");
    var run=new Run();run.id=Ids.next();run.jobId=job.id;
    new SyncEngine(memory.store,config,new TransferService(memory.store)).sync(job,new TaskContext(run,new BandwidthLimiter(0),0));
    var after=helpers();after.removeAll(before);
    assertEquals(Set.of(),after,"every native session must terminate before sync returns");
    Files.move(root.resolve("source"),root.resolve("offline"));
  }
  @Test void directoryTimestampChangesAloneDoNotInvalidateStableChildren() throws Exception {
    var memory=new MemoryStore();var config=mock(ConfigurationService.class);
    var job=new Job();job.id="job";job.sourceId="source";job.targetId="backup";job.archiveId="archive";
    for(String id:List.of(job.sourceId,job.targetId,job.archiveId)) {
      var e=new Endpoint();e.id=id;e.root=Files.createDirectory(root.resolve(id)).toString();when(config.endpoint(id)).thenReturn(e);
    }
    var folder=Files.createDirectory(root.resolve("source/folder"));Files.writeString(folder.resolve("file"),"contents");
    var transfer=new TransferService(memory.store);
    var engine=new SyncEngine(memory.store,config,transfer,e->{
      var actual=new LocalStorage(Path.of(e.root));
      if(!e.id.equals(job.sourceId))return actual;
      var observed=mock(com.oadig.syncsrv.platform.storage.Storage.class,org.mockito.AdditionalAnswers.delegatesTo(actual));
      doAnswer(invocation->{actual.close();Files.setLastModifiedTime(folder,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()+5000));return null;}).when(observed).close();
      return observed;
    });
    var run=new Run();run.id=Ids.next();run.jobId=job.id;
    engine.sync(job,new TaskContext(run,new BandwidthLimiter(0),0));
    assertEquals("contents",Files.readString(root.resolve("backup/folder/file")));
    assertEquals(1,engine.snapshots().size());
  }
  @Test void concurrentReaderStopsAndReleasesItsRootBeforeCloseReturns() throws Exception {
    Path folder=Files.createDirectory(root.resolve("reader"));Files.write(folder.resolve("file"),new byte[1024*1024]);
    var storage=new LocalStorage(folder);var started=new java.util.concurrent.CountDownLatch(1);
    var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var reader=pool.submit(()->{
        try(var input=storage.read("file",0)) { while(input.read()!=-1)started.countDown(); }
        catch(java.io.IOException expected) { /* Closing the owner cancels active reads. */ }
        catch(Exception error) { throw new RuntimeException(error); }
      });
      assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
      storage.close();reader.get(5,java.util.concurrent.TimeUnit.SECONDS);
      Files.delete(folder.resolve("file"));Files.delete(folder);
    } finally {storage.close();pool.shutdownNow();}
  }
  private Set<Long> helpers() {
    var result=new HashSet<Long>();
    ProcessHandle.current().descendants().filter(p->p.info().command().orElse("").contains("syncsrv-local-io-"))
        .forEach(p->result.add(p.pid()));
    return result;
  }
}
