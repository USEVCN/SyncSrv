package com.oadig.syncsrv;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.*;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AgentPushTest {
  @TempDir Path root;
  MemoryStore memory;
  ConfigurationService config;
  Job job;
  AgentPushService push;
  SyncEngine engine;
  TransferService transfer;

  @BeforeEach
  void setup() throws Exception {
    memory = new MemoryStore();
    config = mock(ConfigurationService.class);
    job = new Job();
    job.id = "job";
    job.agentId = "device";
    job.sourceMode = "AGENT";
    job.discoveryMode = "EVENTS";
    job.agentRoot = "C:\\source";
    job.targetId = "B";
    job.archiveId = "C";
    job.enabled = true;
    when(config.job(job.id)).thenReturn(job);
    for (String id : List.of("B", "C")) {
      Endpoint e = new Endpoint();
      e.id = id;
      e.root = Files.createDirectory(root.resolve(id)).toString();
      when(config.endpoint(id)).thenReturn(e);
    }
    var d = new AgentService.Device();
    d.id = "device";
    d.status = "APPROVED";
    memory.store.commit("test", "test", "device", Map.of(), put("agent", d.id, d));
    push = new AgentPushService(memory.store, config);
    transfer = new TransferService(memory.store);
    engine = new SyncEngine(memory.store, config, transfer);
  }

  TaskContext task() {
    Run r = new Run();
    r.id = Ids.next();
    r.actor = "test";
    r.jobId = job.id;
    return new TaskContext(r, new BandwidthLimiter(0), 0);
  }

  Node node(String path, String text) {
    byte[] b = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return new Node(path, false, b.length, 1, Ids.hash(b), "");
  }

  AgentPushService.Session begin(List<String> scopes, List<Node> nodes) throws Exception {
    var s =
        push.begin("device", new AgentPushService.Begin(job.id, Ids.next(), scopes, nodes.size()));
    if (!nodes.isEmpty()) push.page("device", new AgentPushService.Page(s.id, 0, nodes));
    return s;
  }

  void content(Node n, String text) throws Exception {
    var f = push.prepare("device", new AgentPushService.FileRequest(job.id, n));
    if (!f.ready())
      push.chunk(
          "device", job.id, n.hash(), 0, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void inventoryAloneCannotPublishABaselineOrDeleteLiveFiles() throws Exception {
    Files.writeString(root.resolve("B/keep"), "preserve");
    var s=push.begin("device",new AgentPushService.Begin(job.id,Ids.next(),List.of(""),1,"DIRECT"));
    var progress=new SourceTransferProgress(memory.store);progress.begin(s);
    progress.inventory("device",new AgentPushService.Page(s.id,0,List.of(new Node("new",false,10,1,"",""))));
    assertThrows(Exception.class,()->push.finish("device",s.id));
    assertNull(AgentSource.state(memory.store,job.id));
    assertEquals("preserve",Files.readString(root.resolve("B/keep")));
    push.abort("device",s.id);
    assertNull(AgentSource.state(memory.store,job.id));
  }

  @Test
  void sourceUploadsWithoutShareAndHistoryRestoresAfterDeletion() throws Exception {
    Node first = node("file.txt", "v1");
    var s = begin(List.of(""), List.of(first));
    content(first, "v1");
    push.finish("device", s.id);
    engine.sync(job, task());
    Snapshot original = engine.snapshots().get(0);
    assertEquals("v1", Files.readString(root.resolve("B/file.txt")));
    assertFalse(Files.exists(root.resolve("B").resolve(AgentSource.blob(job.id, first.hash()))));
    assertTrue(push.prepare("device", new AgentPushService.FileRequest(job.id, first)).ready());
    Node second = node("file.txt", "v2");
    s = begin(List.of("file.txt"), List.of(second));
    content(second, "v2");
    push.finish("device", s.id);
    engine.sync(job, task(), List.of("file.txt"));
    assertEquals("v2", Files.readString(root.resolve("B/file.txt")));
    s = begin(List.of("file.txt"), List.of());
    push.finish("device", s.id);
    engine.sync(job, task(), List.of("file.txt"));
    assertFalse(Files.exists(root.resolve("B/file.txt")));
    new RestoreService(memory.store, config, engine, transfer)
        .restore(original.id(), root.resolve("D").toString(), task());
    assertEquals("v1", Files.readString(root.resolve("D/file.txt")));
    verify(config, never()).endpoint("");
  }

  @Test
  void incompleteUploadCannotPublishAndLostAcknowledgementResumes() throws Exception {
    Node n = node("file", "abcdef");
    var s = begin(List.of(""), List.of(n));
    push.prepare("device", new AgentPushService.FileRequest(job.id, n));
    assertEquals(3, push.chunk("device", job.id, n.hash(), 0, "abc".getBytes()).offset());
    push = new AgentPushService(memory.store, config);
    assertEquals(3, push.prepare("device", new AgentPushService.FileRequest(job.id, n)).offset());
    assertEquals(3, push.chunk("device", job.id, n.hash(), 0, "abc".getBytes()).offset());
    var incompletePush = push;
    assertThrows(Exception.class, () -> incompletePush.finish("device", s.id));
    assertNull(AgentSource.state(memory.store, job.id));
    assertTrue(push.chunk("device", job.id, n.hash(), 3, "def".getBytes()).ready());
    push.finish("device", s.id);
    assertEquals("PUBLISHED", push.finish("device", s.id).status);
    engine.sync(job, task());
    assertEquals("abcdef", Files.readString(root.resolve("B/file")));
  }

  @Test
  void staleSessionsAndForeignDevicesCannotChangeSource() throws Exception {
    var old = begin(List.of(""), List.of());
    var latest = begin(List.of(""), List.of());
    push.finish("device", latest.id);
    assertThrows(Exception.class, () -> push.finish("device", old.id));
    assertThrows(
        SecurityException.class,
        () -> push.begin("other", new AgentPushService.Begin(job.id, Ids.next(), List.of(""), 0)));
    job.enabled = false;
    assertThrows(
        IllegalStateException.class,
        () -> push.prepare("device", new AgentPushService.FileRequest(job.id, node("x", "x"))));
  }

  @Test
  void corruptBytesResetUploadWithoutPublishingOrTouchingB() throws Exception {
    Node n = node("file", "good");
    var s = begin(List.of(""), List.of(n));
    push.prepare("device", new AgentPushService.FileRequest(job.id, n));
    assertThrows(
        Exception.class, () -> push.chunk("device", job.id, n.hash(), 0, "evil".getBytes()));
    assertEquals(0, push.prepare("device", new AgentPushService.FileRequest(job.id, n)).offset());
    content(n, "good");
    push.finish("device", s.id);
    engine.sync(job, task());
    assertEquals("good", Files.readString(root.resolve("B/file")));
  }

  @Test
  void manifestCannotTraverseOrInventMissingParents() throws Exception {
    var s = begin(List.of(""), List.of());
    assertThrows(
        IllegalArgumentException.class, () -> AgentSource.validate(node("../escape", "bad")));
    Node n = node("missing/child", "x");
    var broken = begin(List.of(""), List.of(n));
    content(n, "x");
    assertThrows(IllegalArgumentException.class, () -> push.finish("device", broken.id));
    assertNull(AgentSource.state(memory.store, job.id));
  }

  @Test void manifestPageRetrySurvivesLostResponseAndServiceRecreation() throws Exception {
    var s=push.begin("device",new AgentPushService.Begin(job.id,Ids.next(),List.of(""),2));
    var first=new AgentPushService.Page(s.id,0,List.of(node("first","a")));
    assertEquals(1,push.page("device",first).received);
    var recreated=new AgentPushService(memory.store,config);
    assertEquals(1,recreated.page("device",first).received);
    assertEquals(1,memory.actions.stream().filter("source.manifest_page"::equals).count());
    assertThrows(IllegalArgumentException.class,()->recreated.page("device",new AgentPushService.Page(s.id,0,List.of(node("first","different")))));
    assertEquals(2,recreated.page("device",new AgentPushService.Page(s.id,1,List.of(node("second","b")))).received);
    assertEquals(2,recreated.page("device",first).received);
    assertEquals(2,memory.actions.stream().filter("source.manifest_page"::equals).count());
  }

  @Test void duplicateManifestPathsAreRejectedBeforeProgressOrDraftIsOverwritten() throws Exception {
    Node n=node("first","a");
    var s=push.begin("device",new AgentPushService.Begin(job.id,Ids.next(),List.of(""),2));
    assertThrows(IllegalArgumentException.class,()->push.page("device",new AgentPushService.Page(s.id,0,List.of(n,n))));
    assertEquals(0,memory.store.get("push-session",s.id,AgentPushService.Session.class).received);
    push.page("device",new AgentPushService.Page(s.id,0,List.of(n)));
    assertThrows(IllegalArgumentException.class,()->push.page("device",new AgentPushService.Page(s.id,1,List.of(node("first","changed")))));
    assertEquals(1,memory.store.get("push-session",s.id,AgentPushService.Session.class).received);
    assertThrows(IllegalArgumentException.class,()->AgentSource.validate(node(".","a")));
  }

  @Test void reusedBeginTokenMustDescribeSameSession() throws Exception {
    String token=Ids.next();var first=new AgentPushService.Begin(job.id,token,List.of(""),1,"DIRECT");
    var s=push.begin("device",first);assertEquals(s.id,push.begin("device",first).id);
    assertThrows(IllegalArgumentException.class,()->push.begin("device",new AgentPushService.Begin(job.id,token,List.of(""),2,"DIRECT")));
    assertThrows(IllegalArgumentException.class,()->push.begin("device",new AgentPushService.Begin(job.id,token,List.of(""),1,"LEGACY_RELAY")));
  }

  @Test void pausedAndRevokedAgentCanAbandonOnlyItsOwnDraft() throws Exception {
    var s=begin(List.of(""),List.of(node("file","data")));job.enabled=false;
    assertThrows(SecurityException.class,()->push.abort("foreign",s.id));
    assertEquals("ABANDONED",push.abort("device",s.id).status);
    long count=memory.actions.stream().filter("source.observation_superseded"::equals).count();
    assertEquals("ABANDONED",push.abort("device",s.id).status);
    assertEquals(count,memory.actions.stream().filter("source.observation_superseded"::equals).count());
    job.enabled=true;var next=begin(List.of(""),List.of(node("next","data")));
    var device=memory.store.get("agent","device",AgentService.Device.class);device.status="REVOKED";memory.put("agent","device",device);
    assertEquals("ABANDONED",push.abort("device",next.id).status);
    assertNull(AgentSource.state(memory.store,job.id));
  }


  @Test void invalidReplacementCannotAbandonAnExistingValidUpload() throws Exception {
    var s=begin(List.of(""),List.of(node("first","a")));
    assertThrows(IllegalArgumentException.class,()->push.begin("device",new AgentPushService.Begin(job.id,Ids.next(),List.of("file"),1,"DIRECT")));
    assertEquals("UPLOADING",memory.store.get("push-session",s.id,AgentPushService.Session.class).status);
    assertEquals(1,memory.store.get("push-session",s.id,AgentPushService.Session.class).received);
  }

}
