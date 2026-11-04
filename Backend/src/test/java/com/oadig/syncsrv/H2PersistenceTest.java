package com.oadig.syncsrv;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;
import static org.junit.jupiter.api.Assertions.*;

import com.oadig.syncsrv.modules.audit.EvidenceVerifier;
import com.oadig.syncsrv.modules.auth.AuthService;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class H2PersistenceTest {
  @TempDir Path directory;
  private Connection admin;
  private Store store;
  @BeforeEach void setup() throws Exception {
    store = open(directory.resolve("data"));
    admin = DriverManager.getConnection(com.oadig.syncsrv.platform.persistence.MetadataTools.url(directory.resolve("data")), "sa", "");
  }
  private Store open(Path path) throws Exception { return new Store(path); }
  @AfterEach void cleanup() throws Exception {
    try { if (store != null) store.close(); }
    finally { if (admin != null) admin.close(); }
  }

  @Test
  void metadataAndAuditCommitAtomicallyAndSurviveReopen() throws Exception {
    store.commit(
        "test",
        "state.saved",
        "中文路径",
        Map.of("path", "目录/😀.txt"),
        put("sample", "1", Map.of("path", "目录/😀.txt")));
    assertThrows(
        SQLException.class,
        () ->
            store.commit(
                "test",
                "x".repeat(200),
                "invalid",
                Map.of(),
                put("sample", "rollback", Map.of("x", 1))));
    assertNull(store.get("sample", "rollback", Map.class));
    assertEquals(1, store.verify().sequence());
    store.close();
    store = null;
    store = open(directory.resolve("data"));
    assertEquals("目录/😀.txt", store.get("sample", "1", Map.class).get("path"));
    assertEquals(1, store.verify().sequence());
  }

  @Test
  void oneDatabaseCannotHaveTwoIndependentWriters() throws Exception {
    assertThrows(Exception.class, () -> open(directory.resolve("data")));
  }

  @Test
  void batchLookupPagesVersionIdsAndVerifiesEverySignature() throws Exception {
    List<Store.Change> changes = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 501; i++) {
      String id = "item-" + i;
      ids.add(id);
      changes.add(put("batch", id, Map.of("index", i)));
    }
    store.commit(
        "test",
        "batch.created",
        "fixture",
        Map.of("count", 501),
        changes.toArray(Store.Change[]::new));
    assertEquals(501, store.getMany("batch", ids, Map.class).size());
    assertEquals(
        500,
        store
            .getMany("batch", List.of("item-500", "missing"), Map.class)
            .get("item-500")
            .get("index"));
    try (Statement s = admin.createStatement()) {
      s.execute("UPDATE objects SET data='{\"index\":99}' WHERE kind='batch' AND id='item-500'");
    }
    assertThrows(Exception.class, () -> store.getMany("batch", ids, Map.class));
  }

  @Test void pagedWritesKeepMixedOperationOrderAndRollBackEveryPageOnAuditFailure() throws Exception {
    var changes = new ArrayList<Store.Change>();
    for (int i = 0; i < 257; i++) changes.add(put("bulk", "item-" + i, Map.of("index", i, "path", "目录/😀.txt")));
    changes.add(put("bulk", "item-0", Map.of("index", -1)));
    changes.add(remove("bulk", "item-0"));
    changes.add(remove("bulk", "item-1"));
    changes.add(put("bulk", "item-0", Map.of("index", 999)));
    store.commit("test", "bulk.created", "fixture", Map.of(), changes.toArray(Store.Change[]::new));
    assertEquals(256, store.list("bulk", Map.class).size());
    assertEquals(999, store.get("bulk", "item-0", Map.class).get("index"));
    assertNull(store.get("bulk", "item-1", Map.class));
    assertEquals(1, store.verify().sequence());
    var deletes = new ArrayList<Store.Change>();
    for (int i = 0; i < 257; i++) deletes.add(remove("bulk", "item-" + i));
    assertThrows(SQLException.class, () -> store.commit("test", "x".repeat(200), "fixture", Map.of(), deletes.toArray(Store.Change[]::new)));
    assertEquals(256, store.list("bulk", Map.class).size(), "all object and signature pages roll back together");
    assertEquals(1, store.verify().sequence());
    store.commit("test", "bulk.deleted", "fixture", Map.of(), deletes.toArray(Store.Change[]::new));
    assertTrue(store.list("bulk", Map.class).isEmpty());
    assertEquals(2, store.verify().sequence());
  }

  @Test void sourceBatchStartAndProgressHaveBoundedDatabaseRoundTrips() throws Exception {
    var job = new Job(); job.id = "job"; job.agentId = "agent";
    job.sourceMode = "AGENT"; job.discoveryMode = "EVENTS"; job.enabled = true; job.intervalSeconds = 0;
    var device = new com.oadig.syncsrv.modules.discovery.AgentService.Device(); device.id = "agent"; device.status = "APPROVED";
    store.commit("test", "fixture.created", "fixture", Map.of(), put("job", job.id, job), put("agent", device.id, device));
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    var field = Store.class.getDeclaredField("db"); field.setAccessible(true);
    Connection actual = (Connection) field.get(store);
    field.set(store, java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
      try {
        Object result = method.invoke(actual, args);
        if (result instanceof Statement statement) {
          Class<?> type = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
          return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getName().startsWith("execute")) requests.incrementAndGet();
            try { return m.invoke(statement, a); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
          });
        }
        return result;
      } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    }));
    var events = new ArrayList<com.oadig.syncsrv.modules.discovery.ChangeInbox.Event>();
    for (int i = 0; i < 256; i++) events.add(new com.oadig.syncsrv.modules.discovery.ChangeInbox.Event("" + (i + 1), "job", "file-" + i, "CHANGE", Ids.now()));
    var batch = new com.oadig.syncsrv.modules.discovery.ChangeInbox.Batch("epoch", events);
    var changes = new com.oadig.syncsrv.modules.discovery.SourceChanges(store, new com.oadig.syncsrv.modules.config.ConfigurationService(store));
    long started = System.nanoTime();
    assertTrue(changes.start("agent", batch).allowed());
    assertTrue(requests.get() <= 25, "256 events must use bulk SQL; statements=" + requests);
    System.out.printf("Source batch 256: start %.0f ms, %d SQL statements%n", (System.nanoTime() - started) / 1e6, requests.get());
    requests.set(0); started = System.nanoTime();
    changes.progress("agent", batch, "UPLOADING_B", "file-42");
    assertTrue(requests.get() <= 8, "file progress must not re-read/write the whole batch; statements=" + requests);
    System.out.printf("Source batch 256: file progress %.0f ms, %d SQL statements%n", (System.nanoTime() - started) / 1e6, requests.get());
    assertEquals(1, changes.view().entries().stream().filter(e -> e.phase.equals("UPLOADING_B")).count());
    assertNotNull(store.verify());
  }

  @Test
  void localTamperingAndTruncatedExportAreDetected() throws Exception {
    store.commit("test", "first", "one", Map.of(), put("sample", "1", Map.of("value", 1)));
    store.commit("test", "second", "two", Map.of());
    AuditHead head = store.verify();
    List<String> lines = new ArrayList<>();
    lines.add(Json.write(store.header(head)));
    store.events(0, 100).forEach(e -> lines.add(Json.write(e)));
    Path file = directory.resolve("evidence.jsonl");
    Files.write(file, lines);
    assertEquals(2, EvidenceVerifier.verify(file, store.publicKey()).sequence());
    Files.write(file, lines.subList(0, lines.size() - 1));
    assertThrows(Exception.class, () -> EvidenceVerifier.verify(file, store.publicKey()));
    try (Statement s = admin.createStatement()) {
      s.execute("UPDATE objects SET data='{\"value\":2}' WHERE kind='sample' AND id='1'");
    }
    assertThrows(Exception.class, () -> store.get("sample", "1", Map.class));
    try (Statement s = admin.createStatement()) {
      s.execute("UPDATE audit SET actor='changed' WHERE seq=1");
    }
    assertThrows(Exception.class, () -> store.verify());
  }

  @Test
  void secretsAreEncryptedAndPasswordBackslashIsLiteral() throws Exception {
    String encoded = store.encrypt("storage-secret");
    assertNotEquals("storage-secret", encoded);
    assertEquals("storage-secret", store.decrypt(encoded));
    AuthService auth = new AuthService(store, "TestPassword\\@2026");
    assertThrows(Exception.class, () -> auth.login("admin", "TestPassword\\\\@2026", "local"));
    String token = auth.login("admin", "TestPassword\\@2026", "local");
    assertEquals("admin", auth.actor(token));
    auth.logout(token);
    assertNull(auth.actor(token));
    assertTrue(
        store.events(0, 100).stream().noneMatch(e -> Json.write(e).contains("TestPassword")));
  }
  private Connection writerConnection() throws Exception {
    var field = Store.class.getDeclaredField("db"); field.setAccessible(true);
    return (Connection) field.get(store);
  }
  private void disconnectWriter() throws Exception { writerConnection().close(); }
  @Test void databaseDisconnectRecoversWithoutRestartAndRetainsWriterExclusivity() throws Exception {
    store.commit("test", "before", "fixture", Map.of(), put("sample", "1", Map.of("value", 1)));
    Connection previous = writerConnection();
    disconnectWriter();
    assertTrue(store.healthy());
    assertNotEquals(previous, writerConnection());
    assertEquals(1, store.get("sample", "1", Map.class).get("value"));
    store.commit("test", "after", "fixture", Map.of(), put("sample", "2", Map.of("value", 2)));
    assertEquals(2, store.verify().sequence());
  }
  @Test void reconnectRetainsDirectoryExclusivity() throws Exception {
    disconnectWriter();
    assertThrows(Exception.class, () -> open(directory.resolve("data")));
    assertTrue(store.healthy());
  }
  @Test void lostCommitAcknowledgementIsReconciledWithoutReplayingTheWrite() throws Exception {
    var field = Store.class.getDeclaredField("db"); field.setAccessible(true);
    Connection actual = (Connection) field.get(store);
    Connection uncertain = (Connection) java.lang.reflect.Proxy.newProxyInstance(
        Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
          try {
            Object result = method.invoke(actual, args);
            if (method.getName().equals("commit")) throw new SQLRecoverableException("fixture: lost commit acknowledgement", "08006");
            return result;
          } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
    field.set(store, uncertain);
    assertThrows(SQLException.class, () -> store.commit("test", "uncertain", "fixture", Map.of(), put("sample", "1", Map.of("value", 1))));
    assertTrue(store.healthy());
    assertEquals(1, store.head().sequence());
    assertEquals(1, store.get("sample", "1", Map.class).get("value"));
    store.commit("test", "next", "fixture", Map.of());
    assertEquals(2, store.verify().sequence());
  }
  @Test void reconnectRejectsAnAuditHeadChangedDuringDisconnection() throws Exception {
    store.commit("test", "before", "fixture", Map.of());
    disconnectWriter();
    try (Statement statement = admin.createStatement()) { statement.execute("DELETE FROM audit"); }
    assertThrows(IllegalStateException.class, () -> store.healthy());
  }

}
