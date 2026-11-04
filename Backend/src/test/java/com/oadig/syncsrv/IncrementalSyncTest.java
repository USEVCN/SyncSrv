package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.ChangeInbox;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.*;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class IncrementalSyncTest {
  @TempDir Path root;
  MemoryStore memory;
  ConfigurationService config;
  SyncEngine engine;
  Job job;
  TransferService transfer;
  Path a, b, c;

  @BeforeEach
  void setup() throws Exception {
    memory = new MemoryStore();
    config = mock(ConfigurationService.class);
    job = new Job();
    job.id = "test-job";
    job.sourceId = "A";
    job.targetId = "B";
    job.archiveId = "C";
    for (String id : List.of("A", "B", "C")) {
      Path p = Files.createDirectory(root.resolve(id));
      Endpoint e = new Endpoint();
      e.id = id;
      e.root = p.toString();
      when(config.endpoint(id)).thenReturn(e);
    }
    a = root.resolve("A");
    b = root.resolve("B");
    c = root.resolve("C");
    when(config.job(job.id)).thenReturn(job);
    transfer = new TransferService(memory.store);
    engine = new SyncEngine(memory.store, config, transfer);
  }

  TaskContext task() {
    Run run = new Run();
    run.id = Ids.next();
    run.jobId = job.id;
    run.actor = "test";
    return new TaskContext(run, new BandwidthLimiter(0), 0);
  }

  @Test
  void changedPathsAvoidUnrelatedContentAndCalibrationRepairsMissedEvents() throws Exception {
    Files.writeString(a.resolve("changed"), "old");
    Files.writeString(a.resolve("quiet"), "old quiet");
    engine.sync(job, task());
    Snapshot first = engine.snapshots().get(0);
    Files.writeString(a.resolve("changed"), "latest");
    Files.writeString(a.resolve("quiet"), "missed event");
    TaskContext incremental = task();
    engine.sync(job, incremental, List.of("changed"));
    assertEquals("EVENTS", incremental.run.discoveryMode);
    assertEquals("latest", Files.readString(b.resolve("changed")));
    assertEquals("old quiet", Files.readString(b.resolve("quiet")));
    assertEquals(2, engine.snapshots().get(0).entries().size());
    var restore = new RestoreService(memory.store, config, engine, transfer);
    restore.restore(first.id(), root.resolve("D").toString(), task());
    assertEquals("old", Files.readString(root.resolve("D/changed")));
    engine.sync(job, task());
    assertEquals("missed event", Files.readString(b.resolve("quiet")));
  }

  @Test
  void newNestedDirectoriesAndDirectoryRemovalAreScopedAndArchived() throws Exception {
    engine.sync(job, task());
    Files.createDirectories(a.resolve("deep/inside"));
    Files.writeString(a.resolve("deep/inside/file"), "content");
    engine.sync(job, task(), List.of("deep/inside/file"));
    assertEquals("content", Files.readString(b.resolve("deep/inside/file")));
    var aggregate =
        new DirectoryIndex(memory.store)
            .list(job.id).stream().filter(s -> s.path().isEmpty()).findFirst().orElseThrow();
    assertEquals(1, aggregate.files());
    assertEquals(2, aggregate.directories());
    assertEquals(7, aggregate.logicalBytes());
    Snapshot before = engine.snapshots().get(0);
    Files.delete(a.resolve("deep/inside/file"));
    Files.delete(a.resolve("deep/inside"));
    Files.delete(a.resolve("deep"));
    engine.sync(job, task(), List.of("deep/inside/file"));
    assertFalse(Files.exists(b.resolve("deep")));
    aggregate =
        new DirectoryIndex(memory.store)
            .list(job.id).stream().filter(s -> s.path().isEmpty()).findFirst().orElseThrow();
    assertEquals(0, aggregate.files());
    assertEquals(0, aggregate.directories());
    assertEquals(0, aggregate.logicalBytes());
    new RestoreService(memory.store, config, engine, transfer)
        .restore(before.id(), root.resolve("D").toString(), task());
    assertEquals("content", Files.readString(root.resolve("D/deep/inside/file")));
  }

  @Test
  void changedTargetCannotBeOverwrittenByScopedRun() throws Exception {
    Files.writeString(a.resolve("file"), "old");
    engine.sync(job, task());
    Files.writeString(a.resolve("file"), "new");
    Files.writeString(b.resolve("file"), "external");
    assertThrows(Exception.class, () -> engine.sync(job, task(), List.of("file")));
    assertEquals("external", Files.readString(b.resolve("file")));
  }

  @Test
  void arrayIndexMigratesAndSubsequentRowsDoNotRewriteWholeArray() throws Exception {
    Node one = new Node("a", false, 1, 0, Ids.textHash("a"), "v1");
    memory.put("current", job.id, new Node[] {one});
    assertEquals(one, engine.current(job.id).get("a"));
    assertNull(memory.store.get("current", job.id, Node[].class));
    assertEquals(one, new SyncEngine(memory.store, config, transfer).current(job.id).get("a"));
  }

  @Test
  void obsoleteUnstagedTransferDoesNotBlockLatestSourceEvenWithIdenticalStat() throws Exception {
    Files.writeString(a.resolve("file"), "old");
    engine.sync(job, task());
    Snapshot original = engine.snapshots().get(0);
    Files.writeString(a.resolve("file"), "in-between");
    var stamp = Files.getLastModifiedTime(a.resolve("file"));
    Node intermediate =
        new Node("file", false, 10, stamp.toMillis(), Ids.textHash("in-between"), "");
    FileOperations operations = new FileOperations(memory.store, transfer);
    Operation pending =
        operations.plan(job, "WRITE", intermediate, engine.current(job.id).get("file"), task());
    Path partial = b.resolve(pending.temp);
    Files.createDirectories(partial.getParent());
    Files.writeString(partial, "in-");
    String checkpoint =
        TransferService.checkpointId(pending.id, pending.path, pending.temp, intermediate.hash());
    memory.put(
        "transfer", checkpoint, new Transfer(checkpoint, intermediate.hash(), 10, 3, "partial"));
    Files.writeString(a.resolve("file"), "new-value!");
    Files.setLastModifiedTime(a.resolve("file"), stamp);
    engine.sync(job, task(), List.of("file"));
    assertEquals("new-value!", Files.readString(b.resolve("file")));
    assertFalse(Files.exists(partial));
    assertNull(memory.store.get("operation", pending.id, Operation.class));
    assertNull(memory.store.get("transfer", checkpoint, Transfer.class));
    assertTrue(memory.actions.contains("operation.superseded_source_changed"));
    new RestoreService(memory.store, config, engine, transfer)
        .restore(original.id(), root.resolve("D").toString(), task());
    assertEquals("old", Files.readString(root.resolve("D/file")));
  }

  @Test
  void duplicateNotificationsDoNotEraseAnEventArrivingDuringBackup() throws Exception {
    ChangeInbox inbox = new ChangeInbox(memory.store);
    var e = new ChangeInbox.Event("1", job.id, "file", "CHANGE", Ids.now(), 7);
    var first = new ChangeInbox.Batch("epoch", List.of(e));
    assertEquals(List.of("1"), inbox.receive("device", first));
    var claim = inbox.claim(job.id);
    assertEquals(7, claim.entries().get(0).sourceCoalesced());
    inbox.receive("device", first);
    assertEquals(1, inbox.pending(job.id).size());
    inbox.receive(
        "device",
        new ChangeInbox.Batch(
            "epoch", List.of(new ChangeInbox.Event("2", job.id, "file", "CHANGE", Ids.now()))));
    inbox.completed(claim, "run-1");
    assertEquals(1, inbox.pending(job.id).size());
    var latest = inbox.claim(job.id);
    inbox.failed(latest, "offline", "run-2");
    assertEquals("offline", inbox.pending(job.id).get(0).error());
    inbox.completed(latest, "run-3");
    assertTrue(inbox.pending(job.id).isEmpty());
    inbox.receive("device", first);
    assertTrue(inbox.pending(job.id).isEmpty());
  }

  @Test
  void reusedEventIdentifierAndTraversalAreRejectedBeforePersistence() throws Exception {
    ChangeInbox inbox = new ChangeInbox(memory.store);
    var e = new ChangeInbox.Event("1", job.id, "file", "CHANGE", Ids.now());
    inbox.receive("device", new ChangeInbox.Batch("epoch", List.of(e)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inbox.receive(
                "device",
                new ChangeInbox.Batch(
                    "epoch",
                    List.of(
                        new ChangeInbox.Event("1", job.id, "other", "CHANGE", e.observedAt())))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inbox.receive(
                "device",
                new ChangeInbox.Batch(
                    "epoch",
                    List.of(
                        new ChangeInbox.Event("2", job.id, "../outside", "CHANGE", Ids.now())))));
    assertEquals(
        List.of("file"), inbox.pending(job.id).stream().map(ChangeInbox.Pending::path).toList());
  }
}
