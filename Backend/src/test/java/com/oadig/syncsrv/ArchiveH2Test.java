package com.oadig.syncsrv;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;
import static org.junit.jupiter.api.Assertions.*;

import com.oadig.syncsrv.modules.archive.*;
import com.oadig.syncsrv.modules.archive.ArchiveModels.*;
import com.oadig.syncsrv.modules.audit.EvidenceVerifier;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.*;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual H2 transactions and signed evidence, isolated from all production objects/files. */
public class ArchiveH2Test {
  @TempDir Path root;
  private Connection admin;
  private Store store;
  private ConfigurationService config;
  private ArchiveService archive;
  private Job job;
  private Snapshot oldest, baseline, latest;
  private Path a, b, c;
  private final String month = YearMonth.now(ZoneId.of("Asia/Shanghai")).minusMonths(1).toString();

  @BeforeEach void setup() throws Exception {
    open();
    admin = DriverManager.getConnection(com.oadig.syncsrv.platform.persistence.MetadataTools.url(root.resolve("data")), "sa", "");
    fixture();
  }
  private void open() throws Exception {
    store = new Store(root.resolve("data"));
    config = new ConfigurationService(store); archive = new ArchiveService(store, config);
  }
  private void reopen() throws Exception { store.close(); store = null; open(); }
  @AfterEach void cleanup() throws Exception {
    try { if (store != null) store.close(); }
    finally { if (admin != null) admin.close(); }
  }

  private void fixture() throws Exception {
    job = new Job(); job.id = Ids.next(); job.name = "isolated archive retention fixture";
    job.sourceId = "A"; job.targetId = "B"; job.archiveId = "C"; job.archiveThreads = 1;
    List<Store.Change> changes = new ArrayList<>();
    for (String id : List.of("A", "B", "C")) {
      Endpoint endpoint = new Endpoint(); endpoint.id = id; endpoint.name = id;
      endpoint.root = Files.createDirectory(root.resolve(id)).toRealPath().toString();
      changes.add(put("endpoint", id, endpoint));
    }
    a = root.resolve("A"); b = root.resolve("B"); c = root.resolve("C");
    changes.add(put("job", job.id, job));
    Version old = version("f.txt", "old", "C", "replaced", changes);
    Version boundary = version("f.txt", "boundary version", "C", "replaced", changes);
    Version current = version("f.txt", "latest version", "B", "current", changes);
    Version shared = version("shared.txt", "shared", "B", "current", changes);
    Version future = version("only-after-boundary.txt", "new file", "B", "current", changes);
    oldest = snapshot(1, -3, List.of(node(old), node(shared)), changes);
    baseline = snapshot(2, -2, List.of(node(boundary), node(shared)), changes);
    latest = snapshot(3, 0, List.of(node(current), node(shared), node(future)), changes);
    changes.add(put("current-index", job.id, new CurrentIndex.Format(1, job.id)));
    for (Node node : latest.entries()) changes.add(CurrentIndex.update(job.id, node.path(), node));
    changes.add(put("sync-checkpoint", job.id,
        new SyncEngine.Checkpoint(latest.completedAt(), latest.id(), latest.entries().size())));
    store.commit("test", "fixture.archive_history", job.id, Map.of("snapshots", 3), changes.toArray(Store.Change[]::new));
    Files.writeString(a.resolve("f.txt"), "latest version"); Files.writeString(a.resolve("shared.txt"), "shared");
    Files.writeString(c.resolve("unregistered.txt"), "must survive cleanup");
  }

  private Version version(String path, String text, String endpoint, String reason,
      List<Store.Change> changes) throws Exception {
    String id = Ids.next(); byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    String location = endpoint.equals("C") ? job.id + "/versions/" + path + ".__v" + id + ".__" + reason : path;
    Path target = root.resolve(endpoint).resolve(location); Files.createDirectories(target.getParent()); Files.write(target, bytes);
    Version version = new Version(id, job.id, path, endpoint, location, bytes.length, Ids.hash(bytes), Ids.now(), reason);
    changes.add(put("version", id, version)); return version;
  }
  private static Node node(Version version) {
    return new Node(version.path(), false, version.size(), 0, version.hash(), version.id());
  }
  private Snapshot snapshot(int sequence, int offset, List<Node> nodes, List<Store.Change> changes) throws Exception {
    String time = YearMonth.now(ZoneId.of("Asia/Shanghai")).plusMonths(offset).atDay(10)
        .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toString();
    Snapshot snapshot = new Snapshot(Ids.next(), job.id, sequence, time, time,
        nodes, Ids.hash(Json.bytes(nodes)), nodes.stream().mapToLong(Node::size).sum());
    changes.add(put("snapshot", snapshot.id(), snapshot));
    changes.add(put("snapshot-summary", snapshot.id(), SnapshotSummary.from(snapshot)));
    Path file = c.resolve(job.id + "/snapshots/" + snapshot.id() + ".json");
    Files.createDirectories(file.getParent()); Files.write(file, Json.bytes(snapshot)); return snapshot;
  }
  private Plan preview() throws Exception {
    Plan plan = archive.preview(job.id, new Request(month, List.of()), "test");
    assertEquals(List.of(oldest.id()), plan.expired.stream().map(SnapshotSummary::id).toList());
    assertEquals(2, plan.items.size()); archive.confirm(job.id, plan.id, true, "test"); return plan;
  }
  private TaskContext task(Plan plan) throws Exception {
    Run run = new Run(); run.id = Ids.next(); run.actor = "test"; run.status = "RUNNING";
    run.startedAt = Ids.now(); run.kind = "ARCHIVE_CLEANUP"; run.jobId = job.id; run.target = plan.id;
    store.commit("test", "fixture.run", run.id, Map.of(), put("run", run.id, run));
    TaskContext task = new TaskContext(run, new BandwidthLimiter(0), 0);
    task.metrics.start(); task.progress.start(); return task;
  }
  private Item oldVersion(Plan plan) {
    return plan.items.stream().filter(item -> item.kind().equals("VERSION")).findFirst().orElseThrow();
  }
  private Map<?, ?> outcome(Plan plan, Item item) throws Exception {
    return store.get("archive-item", Ids.textHash(plan.id + ":" + item.id()), Map.class);
  }
  private List<AuditEvent> events(String action) throws Exception {
    return store.events(0, 10000).stream().filter(e -> e.action().equals(action)).toList();
  }
  private static boolean isItem(AuditEvent event, Item item) {
    Map<?, ?> detail = Json.read(event.detail(), Map.class);
    return detail.get("item") instanceof Map<?, ?> value && item.id().equals(value.get("id"));
  }
  private void assertSignedEvidence() throws Exception {
    AuditHead head = store.verify(); assertTrue(head.sequence() > 0);
    List<String> lines = new ArrayList<>(); lines.add(Json.write(store.header(head)));
    store.events(0, 10000).forEach(event -> lines.add(Json.write(event)));
    Path export = root.resolve("audit.jsonl"); Files.write(export, lines);
    AuditHead verified = EvidenceVerifier.verify(export, store.publicKey());
    assertEquals(head.sequence(), verified.sequence()); assertEquals(head.head(), verified.head());
  }
  private void rejectAudit(String action, Item item) throws Exception {
    try (Statement statement = admin.createStatement()) {
      statement.execute("CREATE TABLE failure_scope(action VARCHAR(96), item_id VARCHAR(64))");
      statement.execute("CREATE TRIGGER reject_archive_audit BEFORE INSERT ON audit FOR EACH ROW CALL 'com.oadig.syncsrv.ArchiveH2Test$RejectAudit'");
    }
    try (PreparedStatement statement = admin.prepareStatement("INSERT INTO failure_scope VALUES(?,?)")) {
      statement.setString(1, action); statement.setString(2, item == null ? null : item.id()); statement.executeUpdate();
    }
  }
  public static class RejectAudit implements org.h2.api.Trigger {
    @Override public void fire(Connection connection, Object[] oldRow, Object[] row) throws SQLException {
      try (Statement statement = connection.createStatement(); ResultSet scope = statement.executeQuery("SELECT action,item_id FROM failure_scope")) {
        if (!scope.next() || !Objects.equals(scope.getString(1), row[3])) return;
        String id = scope.getString(2);
        if (id != null) {
          String detail = row[5] instanceof Clob clob ? clob.getSubString(1, Math.toIntExact(clob.length())) : row[5].toString();
          Map<?, ?> value = Json.read(detail, Map.class);
          if (!(value.get("item") instanceof Map<?, ?> item) || !id.equals(item.get("id"))) return;
        }
        throw new SQLException("fixture archive audit failure", "45000");
      }
    }
  }
  private static boolean hasSqlState(Throwable error, String state) {
    if (error instanceof SQLException sql && state.equals(sql.getSQLState())) return true;
    if (error.getCause() != null && hasSqlState(error.getCause(), state)) return true;
    for (Throwable suppressed : error.getSuppressed()) if (hasSqlState(suppressed, state)) return true;
    return false;
  }
  private void allowAudit() throws Exception {
    try (Statement statement = admin.createStatement()) {
      statement.execute("DROP TRIGGER IF EXISTS reject_archive_audit");
    }
  }

  @Test void retiresSnapshotsAndDeletesEachFileWithAtomicSignedReceipts() throws Exception {
    Plan plan = preview(); Map<String, String> source = ArchiveCleanupTest.contents(a), current = ArchiveCleanupTest.contents(b);
    archive.execute(job.id, plan.id, task(plan));
    Plan completed = archive.get(job.id, plan.id);
    assertEquals("COMPLETED", completed.state); assertEquals(plan.items.size(), completed.completedFiles);
    assertEquals(plan.estimatedBytes, completed.deletedBytes);
    assertNull(store.get("snapshot", oldest.id(), Snapshot.class));
    assertNull(store.get("snapshot-summary", oldest.id(), SnapshotSummary.class));
    assertNotNull(store.get("retired-snapshot", oldest.id(), ArchiveService.RetiredSnapshot.class));
    long retired = events("archive.snapshots_retired").get(0).sequence();
    for (Item item : plan.items) {
      assertFalse(Files.exists(c.resolve(item.location()))); assertEquals("REMOVED", outcome(plan, item).get("state"));
      for (String id : item.versionIds()) assertNull(store.get("version", id, Version.class));
      List<AuditEvent> intent = events("archive.remove_intent").stream().filter(e -> isItem(e, item)).toList();
      List<AuditEvent> receipt = events("archive.file_removed").stream().filter(e -> isItem(e, item)).toList();
      assertEquals(1, intent.size()); assertEquals(1, receipt.size());
      assertTrue(retired < intent.get(0).sequence()); assertTrue(intent.get(0).sequence() < receipt.get(0).sequence());
    }
    assertEquals(source, ArchiveCleanupTest.contents(a)); assertEquals(current, ArchiveCleanupTest.contents(b));
    assertEquals("must survive cleanup", Files.readString(c.resolve("unregistered.txt")));
    reopen(); assertEquals("COMPLETED", archive.get(job.id, plan.id).state);
    TransferService transfer = new TransferService(store); SyncEngine sync = new SyncEngine(store, config, transfer);
    assertThrows(Exception.class, () -> sync.snapshot(oldest.id()));
    Path restore = root.resolve("reconstructed");
    new RestoreService(store, config, sync, transfer).restore(baseline.id(), restore.toString(), task(plan));
    assertEquals("boundary version", Files.readString(restore.resolve("f.txt")));
    assertEquals("shared", Files.readString(restore.resolve("shared.txt")));
    assertFalse(Files.exists(restore.resolve("only-after-boundary.txt"))); assertSignedEvidence();
  }

  @Test void retirementAuditFailureRollsBackEveryMetadataChangeBeforeDeletion() throws Exception {
    Plan plan = preview(); TaskContext execution = task(plan); Map<String, String> files = ArchiveCleanupTest.contents(c);
    rejectAudit("archive.snapshots_retired", null);
    Exception failure = assertThrows(Exception.class, () -> archive.execute(job.id, plan.id, execution));
    assertTrue(hasSqlState(failure, "45000"), "the injected H2 audit failure must be reached");
    assertEquals(files, ArchiveCleanupTest.contents(c));
    assertNotNull(store.get("snapshot", oldest.id(), Snapshot.class));
    assertNotNull(store.get("snapshot-summary", oldest.id(), SnapshotSummary.class));
    assertNull(store.get("retired-snapshot", oldest.id(), ArchiveService.RetiredSnapshot.class));
    assertNull(store.get("archive-retirement", plan.id, ArchiveService.Retirement.class));
    assertTrue(events("archive.snapshots_retired").isEmpty()); assertTrue(events("archive.remove_intent").isEmpty());
    allowAudit(); reopen(); archive.execute(job.id, plan.id, task(plan));
    assertEquals("COMPLETED", archive.get(job.id, plan.id).state); assertSignedEvidence();
  }

  @Test void failedDeleteIntentCannotLeaveUnsignedIntentOrRemoveAnyFile() throws Exception {
    Plan plan = preview(); TaskContext execution = task(plan); Map<String, String> files = ArchiveCleanupTest.contents(c);
    rejectAudit("archive.remove_intent", null);
    Exception failure = assertThrows(Exception.class, () -> archive.execute(job.id, plan.id, execution));
    assertTrue(hasSqlState(failure, "45000"), "the injected H2 audit failure must be reached");
    assertEquals(files, ArchiveCleanupTest.contents(c));
    for (Item item : plan.items) assertNull(outcome(plan, item));
    assertTrue(events("archive.remove_intent").isEmpty()); assertTrue(events("archive.file_removed").isEmpty());
    assertNotNull(store.get("archive-retirement", plan.id, ArchiveService.Retirement.class));
    allowAudit(); reopen(); archive.execute(job.id, plan.id, task(plan));
    assertEquals("COMPLETED", archive.get(job.id, plan.id).state); assertSignedEvidence();
  }

  @Test void failedRemovalReceiptPreservesIntentAndVersionUntilReopenConfirmsAbsence() throws Exception {
    Plan plan = preview(); Item old = oldVersion(plan); TaskContext execution = task(plan);
    rejectAudit("archive.file_removed", old);
    Exception failure = assertThrows(Exception.class, () -> archive.execute(job.id, plan.id, execution));
    assertTrue(hasSqlState(failure, "45000"), "the injected H2 audit failure must be reached");
    assertFalse(Files.exists(c.resolve(old.location())), "physical deletion reached the injected receipt failure");
    assertEquals("INTENT", outcome(plan, old).get("state"));
    for (String id : old.versionIds()) assertNotNull(store.get("version", id, Version.class));
    assertTrue(events("archive.file_removed").stream().noneMatch(event -> isItem(event, old)));
    allowAudit(); reopen(); archive.execute(job.id, plan.id, task(plan));
    Plan completed = archive.get(job.id, plan.id);
    assertEquals("COMPLETED", completed.state); assertEquals(1, completed.missingFiles);
    assertEquals(plan.estimatedBytes - old.size(), completed.deletedBytes, "uncertain deletion must not inflate reclaimed bytes");
    assertEquals("ABSENT_AFTER_INTENT", outcome(plan, old).get("state"));
    for (String id : old.versionIds()) assertNull(store.get("version", id, Version.class));
    assertEquals(1, events("archive.snapshots_retired").size());
    assertEquals(1, events("archive.file_absent_after_intent").stream().filter(event -> isItem(event, old)).count());
    assertSignedEvidence();
  }

  @Test void lostRetirementCommitAcknowledgementReopensWithoutRetiringOrDeletingTwice() throws Exception {
    Plan plan = preview(); TaskContext execution = task(plan); Map<String, String> files = ArchiveCleanupTest.contents(c);
    Field field = Store.class.getDeclaredField("db"); field.setAccessible(true);
    Connection actual = (Connection) field.get(store); AtomicBoolean failOnce = new AtomicBoolean(true);
    Connection uncertain = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
        new Class<?>[] {Connection.class}, (proxy, method, args) -> {
          try {
            Object result = method.invoke(actual, args);
            if (method.getName().equals("commit") && failOnce.compareAndSet(true, false))
              throw new SQLRecoverableException("fixture lost retirement commit acknowledgement", "08006");
            return result;
          } catch (InvocationTargetException error) { throw error.getCause(); }
        });
    field.set(store, uncertain);
    Exception failure = assertThrows(Exception.class, () -> archive.execute(job.id, plan.id, execution));
    assertTrue(hasSqlState(failure, "08006"));
    assertFalse(failOnce.get(), "the actual JDBC commit was reached"); assertEquals(files, ArchiveCleanupTest.contents(c));
    assertNull(store.get("snapshot", oldest.id(), Snapshot.class));
    assertNotNull(store.get("retired-snapshot", oldest.id(), ArchiveService.RetiredSnapshot.class));
    assertNotNull(store.get("archive-retirement", plan.id, ArchiveService.Retirement.class));
    assertEquals(1, events("archive.snapshots_retired").size());
    reopen(); archive.execute(job.id, plan.id, task(plan));
    assertEquals("COMPLETED", archive.get(job.id, plan.id).state);
    assertEquals(1, events("archive.snapshots_retired").size());
    assertEquals(plan.items.size(), events("archive.file_removed").size()); assertSignedEvidence();
  }
}
