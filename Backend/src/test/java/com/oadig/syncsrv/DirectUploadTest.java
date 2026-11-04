package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.discovery.*;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.*;
import com.oadig.syncsrv.modules.transfer.TransferService;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DirectUploadTest {
  @TempDir Path root;
  AgentPushTest f;
  DirectUploadService direct;

  @BeforeEach
  void setup() throws Exception {
    f = new AgentPushTest();
    f.root = root;
    f.setup();
    direct = new DirectUploadService(f.memory.store, f.config, f.push);
    f.transfer = spy(new TransferService(f.memory.store));
    f.engine = new SyncEngine(f.memory.store, f.config, f.transfer);
  }

  AgentPushService.Session start(List<Node> nodes) throws Exception {
    var s =
        f.push.begin(
            "device",
            new AgentPushService.Begin(f.job.id, Ids.next(), List.of(""), nodes.size(), "DIRECT"));
    if (!nodes.isEmpty()) f.push.page("device", new AgentPushService.Page(s.id, 0, nodes));
    return s;
  }

  void upload(AgentPushService.Session s, Node n, String text) throws Exception {
    var request = new DirectUploadService.FileRequest(s.id, n);
    var ticket = direct.prepare("device", request);
    if (!ticket.ready()) {
      Path path = root.resolve("B").resolve(ticket.path());
      Files.createDirectories(path.getParent());
      Files.writeString(path, text);
      direct.complete("device", request);
    }
  }

  @Test
  void duplicateContentIsCommittedInsideBWithoutCenterCopyAndRestoresToConnection()
      throws Exception {
    Node a = f.node("a", "first"), b = f.node("b", "first");
    var s = start(List.of(a, b));
    upload(s, a, "first");
    upload(s, b, "first");
    f.push.finish("device", s.id);
    f.engine.sync(f.job, f.task());
    String original = f.engine.snapshots().get(0).id();
    assertEquals("first", Files.readString(root.resolve("B/a")));
    assertEquals("first", Files.readString(root.resolve("B/b")));
    verify(f.transfer, never())
        .copy(
            isA(AgentStorage.class),
            anyString(),
            any(),
            anyString(),
            anyLong(),
            anyString(),
            anyString(),
            any());
    Node next = f.node("a", "second");
    s = start(List.of(next));
    upload(s, next, "second");
    f.push.finish("device", s.id);
    f.engine.sync(f.job, f.task());
    assertFalse(Files.exists(root.resolve("B/b")));
    Endpoint d = new Endpoint();
    d.id = "D";
    d.root = Files.createDirectory(root.resolve("D")).toString();
    when(f.config.endpoint("D")).thenReturn(d);
    RestoreService restore = new RestoreService(f.memory.store, f.config, f.engine, f.transfer);
    restore.restore(original, "D", "snapshot/one", f.task());
    assertEquals("first", Files.readString(root.resolve("D/snapshot/one/a")));
    assertEquals("first", Files.readString(root.resolve("D/snapshot/one/b")));
    restore.restore(original, "D", "snapshot/one", f.task()); // same node is idempotent
    String latest = f.engine.snapshots().get(0).id();
    assertThrows(Exception.class, () -> restore.restore(latest, "D", "snapshot/one", f.task()));
    assertThrows(Exception.class, () -> restore.restore(original, "D", "../escape", f.task()));
  }

  @Test
  void resumesAfterBStageRenameBeforeDatabaseAcknowledgement() throws Exception {
    Node n = f.node("a", "data");
    var session = start(List.of(n));
    upload(session, n, "data");
    f.push.finish("device", session.id);
    var task = f.task();
    var op = new FileOperations(f.memory.store, f.transfer).plan(f.job, "WRITE", n, null, task);
    try (var source = new AgentStorage(f.memory.store, f.config, f.job);
        var target =
            com.oadig.syncsrv.platform.storage.StorageFactory.open(f.config.endpoint("B"))) {
      source.stage(n.path(), target, op.temp);
    }
    f.engine.sync(f.job, f.task());
    assertEquals("data", Files.readString(root.resolve("B/a")));
    assertTrue(f.memory.store.list("operation", Operation.class).isEmpty());
    verify(f.transfer, never()).hash(isA(AgentStorage.class), anyString(), anyLong(), any());
  }

  @Test
  void incompleteOrForeignDirectReceiptsCannotPublish() throws Exception {
    Node n = f.node("a", "data");
    var s = start(List.of(n));
    var request = new DirectUploadService.FileRequest(s.id, n);
    direct.prepare("device", request);
    assertThrows(Exception.class, () -> direct.complete("device", request));
    assertThrows(Exception.class, () -> f.push.finish("device", s.id));
    assertThrows(Exception.class, () -> direct.prepare("foreign", request));
    assertThrows(
        Exception.class,
        () ->
            direct.prepare(
                "device", new DirectUploadService.FileRequest(s.id, f.node("unknown", "x"))));
    f.push.abort("device", s.id);
    assertThrows(Exception.class, () -> direct.complete("device", request));
    assertNull(AgentSource.state(f.memory.store, f.job.id));
  }

  @Test
  void batchUsesOneConnectionAndCommitsOnlyWhenEveryFileExists() throws Exception {
    Node a=f.node("a","one"), b=f.node("b","two");
    var s=start(List.of(a,b));
    var request=new DirectUploadService.BatchRequest(s.id,List.of(a,b));
    var tickets=direct.prepareBatch("device",request);
    Path first=root.resolve("B").resolve(tickets.get(0).path());
    Files.createDirectories(first.getParent());Files.writeString(first,"one");
    assertThrows(Exception.class,()->direct.completeBatch("device",request));
    assertTrue(f.memory.store.get(DirectUploadService.kind(f.job.id),DirectUploadService.key(a),DirectUploadService.Content.class).verifiedAt().isBlank());
    Files.writeString(root.resolve("B").resolve(tickets.get(1).path()),"two");
    var endpoint=f.config.endpoint("B");
    var storage=spy(com.oadig.syncsrv.platform.storage.StorageFactory.open(endpoint));
    try(var factory=mockStatic(com.oadig.syncsrv.platform.storage.StorageFactory.class)) {
      factory.when(()->com.oadig.syncsrv.platform.storage.StorageFactory.open(endpoint)).thenReturn(storage);
      direct.completeBatch("device",request);
      factory.verify(()->com.oadig.syncsrv.platform.storage.StorageFactory.open(endpoint),times(1));
      verify(storage,times(2)).stat(anyString());
      verify(storage).close();
    }
    long receipts=f.memory.actions.stream().filter("source.direct_verified"::equals).count();
    direct.completeBatch("device",request);
    assertEquals(receipts,f.memory.actions.stream().filter("source.direct_verified"::equals).count());
    f.push.finish("device",s.id);
    assertNotNull(AgentSource.state(f.memory.store,f.job.id));
  }

  @Test
  void batchRejectsDuplicateForeignAndCancelledReceipts() throws Exception {
    Node a=f.node("a","data");var s=start(List.of(a));
    assertThrows(Exception.class,()->direct.prepareBatch("device",new DirectUploadService.BatchRequest(s.id,List.of(a,a))));
    assertThrows(Exception.class,()->direct.prepareBatch("foreign",new DirectUploadService.BatchRequest(s.id,List.of(a))));
    assertThrows(Exception.class,()->direct.prepareBatch("device",new DirectUploadService.BatchRequest(s.id,Collections.nCopies(33,a))));
    var request=new DirectUploadService.BatchRequest(s.id,List.of(a));
    var ticket=direct.prepareBatch("device",request).get(0);
    Path path=root.resolve("B").resolve(ticket.path());Files.createDirectories(path.getParent());Files.writeString(path,"data");
    var endpoint=f.config.endpoint("B");
    var storage=spy(com.oadig.syncsrv.platform.storage.StorageFactory.open(endpoint));
    doAnswer(invocation->{
      assertFalse(Thread.holdsLock(f.push));assertFalse(Thread.holdsLock(f.memory.store));
      f.push.abort("device",s.id);return invocation.callRealMethod();
    }).when(storage).stat(anyString());
    try(var factory=mockStatic(com.oadig.syncsrv.platform.storage.StorageFactory.class)) {
      factory.when(()->com.oadig.syncsrv.platform.storage.StorageFactory.open(endpoint)).thenReturn(storage);
      assertThrows(Exception.class,()->direct.completeBatch("device",request));
    }
    assertTrue(f.memory.store.get(DirectUploadService.kind(f.job.id),DirectUploadService.key(a),DirectUploadService.Content.class).verifiedAt().isBlank());
  }

  @Test
  void finishDoesNotHoldStoreLockDuringStorageIoAndRechecksAbort() throws Exception {
    Node a=f.node("a","data");var s=start(List.of(a));upload(s,a,"data");
    var endpoint=f.config.endpoint("B");
    var storage=spy(com.oadig.syncsrv.platform.storage.StorageFactory.open(endpoint));
    doAnswer(invocation->{
      assertFalse(Thread.holdsLock(f.memory.store));assertFalse(Thread.holdsLock(f.push));
      f.push.abort("device",s.id);return invocation.callRealMethod();
    }).when(storage).stat(anyString());
    try(var factory=mockStatic(com.oadig.syncsrv.platform.storage.StorageFactory.class)) {
      factory.when(()->com.oadig.syncsrv.platform.storage.StorageFactory.open(endpoint)).thenReturn(storage);
      assertThrows(Exception.class,()->f.push.finish("device",s.id));
    }
    assertNull(AgentSource.state(f.memory.store,f.job.id));
  }

  @Test
  void largeFinishUsesDirectoryListingAndStillRejectsMissingContent() throws Exception {
    List<Node> nodes=new ArrayList<>();for(int i=0;i<32;i++)nodes.add(f.node("file"+i,"data"));
    var s=start(nodes);var request=new DirectUploadService.BatchRequest(s.id,nodes);
    var tickets=direct.prepareBatch("device",request);
    for(var ticket:tickets){Path path=root.resolve("B").resolve(ticket.path());Files.createDirectories(path.getParent());Files.writeString(path,"data");}
    direct.completeBatch("device",request);
    var endpoint=f.config.endpoint("B");var storage=spy(com.oadig.syncsrv.platform.storage.StorageFactory.open(endpoint));
    DirectUploadService.verifyReady(f.memory.store,f.job.id,nodes,storage);
    verify(storage,times(1)).list(anyString());verify(storage,never()).stat(anyString());
    Files.delete(root.resolve("B").resolve(tickets.get(15).path()));
    assertThrows(Exception.class,()->f.push.finish("device",s.id));
    assertNull(AgentSource.state(f.memory.store,f.job.id));
    storage.close();
  }

  @Test void cleanupDoesNotBlockGlobalDatabaseMonitorDuringNasIo() throws Exception {
    Node a=f.node("a","data");var s=start(List.of(a));upload(s,a,"data");f.push.abort("device",s.id);
    var endpoint=f.config.endpoint("B");
    var storage=spy(com.oadig.syncsrv.platform.storage.StorageFactory.open(endpoint));
    doAnswer(invocation->{assertFalse(Thread.holdsLock(f.memory.store));return invocation.callRealMethod();}).when(storage).stat(anyString());
    doAnswer(invocation->{assertFalse(Thread.holdsLock(f.memory.store));return invocation.callRealMethod();}).when(storage).delete(anyString(),anyBoolean());
    try(var factory=mockStatic(com.oadig.syncsrv.platform.storage.StorageFactory.class)) {
      factory.when(()->com.oadig.syncsrv.platform.storage.StorageFactory.open(endpoint)).thenReturn(storage);
      new AgentPushService(f.memory.store,f.config).cleanup(f.job);
    }
    assertTrue(f.memory.store.list(DirectUploadService.kind(f.job.id),DirectUploadService.Content.class).isEmpty());
    verify(storage).delete(anyString(),eq(false));
  }

}
