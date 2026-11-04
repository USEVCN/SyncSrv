package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.*;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SyncRecoveryTest {
  @TempDir Path root;
  private MemoryStore memory;
  private ConfigurationService config;
  private SyncEngine engine;
  private TransferService transfer;
  private RestoreService restore;
  private Job job;
  private Path a, b, c;

  @BeforeEach
  void setup() throws Exception {
    a = Files.createDirectory(root.resolve("A"));
    b = Files.createDirectory(root.resolve("B"));
    c = Files.createDirectory(root.resolve("C"));
    memory = new MemoryStore();
    config = mock(ConfigurationService.class);
    job = new Job();
    job.id = "job";
    job.sourceId = "A";
    job.targetId = "B";
    job.archiveId = "C";
    for (String id : List.of("A", "B", "C")) {
      Endpoint e = new Endpoint();
      e.id = id;
      e.root = root.resolve(id).toString();
      when(config.endpoint(id)).thenReturn(e);
    }
    when(config.job("job")).thenReturn(job);
    transfer = new TransferService(memory.store);
    engine = new SyncEngine(memory.store, config, transfer);
    restore = new RestoreService(memory.store, config, engine, transfer);
  }

  private TaskContext task() {
    Run r = new Run();
    r.id = Ids.next();
    r.actor = "test";
    r.jobId = job.id;
    return new TaskContext(r, new BandwidthLimiter(0), 0);
  }

  private Snapshot sync() throws Exception {
    TaskContext task=task();
    engine.sync(job, task);
    var progress=task.progress.snapshot(task.run);
    assertTrue(progress.planComplete());
    assertEquals(progress.totalFiles(),progress.processedFiles());
    assertEquals(progress.totalBytes(),progress.processedBytes());
    return engine.snapshots().get(0);
  }

  @Test
  void versionsDeletionsAndPointInTimeReconstruction() throws Exception {
    Files.createDirectories(a.resolve("中文/空目录"));
    Files.writeString(a.resolve("中文/document.txt"), "version one");
    Files.writeString(a.resolve("deleted.txt"), "keep history");
    Snapshot first = sync();
    long unchanged = Files.getLastModifiedTime(b.resolve("deleted.txt")).toMillis();
    Files.writeString(a.resolve("中文/document.txt"), "version two");
    Files.writeString(a.resolve("new.txt"), "new later");
    Snapshot second = sync();
    assertEquals(unchanged, Files.getLastModifiedTime(b.resolve("deleted.txt")).toMillis());
    Files.delete(a.resolve("deleted.txt"));
    Snapshot third = sync();
    assertFalse(Files.exists(b.resolve("deleted.txt")));
    assertTrue(
        memory.store.list("version", Version.class).stream()
            .anyMatch(v -> v.reason().equals("deleted") && v.location().contains(".__deleted")));
    RestoreService.Plan plan = restore.plan(first.id());
    assertEquals(List.of("new.txt"), plan.excludedCurrentPaths());
    assertTrue(plan.items().stream().allMatch(i -> i.decision().equals("HISTORY_C")));
    byte[] before = treeHash(b, c);
    Path destination = root.resolve("D");
    restore.restore(first.id(), destination.toString(), task());
    assertEquals("version one", Files.readString(destination.resolve("中文/document.txt")));
    assertEquals("keep history", Files.readString(destination.resolve("deleted.txt")));
    assertFalse(Files.exists(destination.resolve("new.txt")));
    assertTrue(Files.isDirectory(destination.resolve("中文/空目录")));
    assertArrayEquals(before, treeHash(b, c));
    Path secondD = root.resolve("D2");
    restore.restore(second.id(), secondD.toString(), task());
    assertEquals("version two", Files.readString(secondD.resolve("中文/document.txt")));
    assertTrue(Files.exists(secondD.resolve("deleted.txt")));
    Path thirdD = root.resolve("D3");
    restore.restore(third.id(), thirdD.toString(), task());
    assertFalse(Files.exists(thirdD.resolve("deleted.txt")));
  }

  @Test
  void fileDirectoryTypeChangesAndEmptySnapshot() throws Exception {
    Files.writeString(a.resolve("item"), "file");
    sync();
    Files.delete(a.resolve("item"));
    Files.createDirectory(a.resolve("item"));
    Files.writeString(a.resolve("item/child"), "child");
    sync();
    assertTrue(Files.isDirectory(b.resolve("item")));
    Files.delete(a.resolve("item/child"));
    Files.delete(a.resolve("item"));
    Files.writeString(a.resolve("item"), "back to file");
    sync();
    assertEquals("back to file", Files.readString(b.resolve("item")));
    Files.delete(a.resolve("item"));
    Snapshot empty = sync();
    assertTrue(empty.entries().isEmpty());
    restore.restore(empty.id(), root.resolve("empty").toString(), task());
    try (var files = Files.list(root.resolve("empty"))) {
      assertEquals(List.of(".syncsrv"), files.map(p -> p.getFileName().toString()).toList());
    }
  }

  @Test
  void unreachableSourceNeverDeletesCurrentCopy() throws Exception {
    Files.writeString(a.resolve("x"), "safe");
    sync();
    Files.move(a, root.resolve("offline"));
    assertThrows(Exception.class, this::sync);
    assertEquals("safe", Files.readString(b.resolve("x")));
    assertEquals(1, engine.snapshots().size());
  }

  @Test
  void changedBAndCorruptArchiveStopWithoutOverwriting() throws Exception {
    Files.writeString(a.resolve("x"), "old");
    Snapshot first = sync();
    Files.writeString(b.resolve("x"), "manually changed");
    Files.writeString(a.resolve("x"), "new");
    assertThrows(Exception.class, this::sync);
    assertEquals("manually changed", Files.readString(b.resolve("x")));
    Files.writeString(b.resolve("x"), "old");
    sync();
    RestoreItem old = restore.plan(first.id()).items().get(0);
    Files.writeString(c.resolve(old.location()), "bad");
    assertThrows(
        Exception.class,
        () -> restore.restore(first.id(), root.resolve("restore").toString(), task()));
    assertEquals("new", Files.readString(b.resolve("x")));
  }

  @Test
  void archiveVerifiedBeforeCrashCanFinishWithoutSource() throws Exception {
    Files.writeString(a.resolve("x"), "old");
    sync();
    Node old = engine.current(job.id).get("x");
    Files.writeString(a.resolve("x"), "new");
    Node fresh = new Node("x", false, 3, 0, Ids.textHash("new"), "");
    FileOperations operations = new FileOperations(memory.store, transfer);
    TaskContext t = task();
    Operation op = operations.plan(job, "WRITE", fresh, old, t);
    try (Storage as = new LocalStorage(a);
        Storage bs = new LocalStorage(b);
        Storage cs = new LocalStorage(c)) {
      Storage interrupted =
          new TransferTest.Delegate(bs) {
            public void move(String from, String to) throws Exception {
              throw new java.io.IOException("power loss before rename");
            }
          };
      assertThrows(
          Exception.class,
          () -> operations.apply(op, job, as, interrupted, cs, engine.current(job.id), t));
      assertFalse(Files.exists(b.resolve("x")));
      assertTrue(Files.exists(c.resolve(op.archivePath)));
      Files.delete(a.resolve("x"));
      Operation pending = memory.store.get("operation", op.id, Operation.class);
      operations.apply(pending, job, as, bs, cs, engine.current(job.id), task());
      assertEquals("new", Files.readString(b.resolve("x")));
      assertTrue(memory.store.list("operation", Operation.class).isEmpty());
    }
  }

  @Test
  void freshlyVerifiedArchiveIsNotFullyHashedTwice() throws Exception {
    Files.writeString(a.resolve("x"), "old");
    sync();
    Node old = engine.current(job.id).get("x");
    Files.delete(a.resolve("x"));
    FileOperations ops = new FileOperations(memory.store, transfer);
    TaskContext t = task();
    Operation op = ops.plan(job, "DELETE", old, old, t);
    try (Storage as = new LocalStorage(a); Storage bs = new LocalStorage(b); Storage cs = new LocalStorage(c)) {
      int[] archiveReads = {0}, sourceReads = {0};
      Storage measuredB = new TransferTest.Delegate(bs) {
        public java.io.InputStream read(String path, long offset) throws Exception {
          sourceReads[0]++; return super.read(path, offset);
        }
      };
      Storage measured = new TransferTest.Delegate(cs) {
        public java.io.InputStream read(String path, long offset) throws Exception {
          archiveReads[0]++;
          return super.read(path, offset);
        }
      };
      ops.apply(op, job, as, measuredB, measured, engine.current(job.id), t);
      // One chunk read-back and one final full hash. No duplicate full hash after ARCHIVED.
      assertEquals(2, archiveReads[0]);
      assertEquals(2, sourceReads[0]); // Relay read plus mandatory pre-delete hash, no pre-copy hash.
      assertFalse(Files.exists(b.resolve("x")));
      assertEquals("old", Files.readString(c.resolve(op.archivePath)));
    }
  }

  @Test
  void recoveryRevalidatesArchiveBeforeRemovingB() throws Exception {
    Files.writeString(a.resolve("x"), "old");
    sync();
    Node old = engine.current(job.id).get("x");
    Files.delete(a.resolve("x"));
    FileOperations ops = new FileOperations(memory.store, transfer);
    TaskContext t = task();
    Operation op = ops.plan(job, "DELETE", old, old, t);
    try (Storage as = new LocalStorage(a); Storage bs = new LocalStorage(b); Storage cs = new LocalStorage(c)) {
      Storage interrupted = new TransferTest.Delegate(bs) {
        public void delete(String p, boolean directory) throws Exception {
          throw new java.io.IOException("power loss before removal");
        }
      };
      assertThrows(Exception.class, () -> ops.apply(op, job, as, interrupted, cs, engine.current(job.id), t));
      Operation pending = memory.store.get("operation", op.id, Operation.class);
      assertEquals("ARCHIVED", pending.phase);
      Files.writeString(c.resolve(op.archivePath), "bad");
      assertThrows(Exception.class, () -> ops.apply(pending, job, as, bs, cs, engine.current(job.id), task()));
      assertEquals("old", Files.readString(b.resolve("x")));
      assertNotNull(memory.store.get("operation", op.id, Operation.class));
    }
  }

  @Test
  void sameSizeSourceMutationCannotBeArchivedAsTheOldVersion() throws Exception {
    Files.writeString(a.resolve("x"), "old"); sync();
    Node old = engine.current(job.id).get("x"); Files.delete(a.resolve("x"));
    var ops = new FileOperations(memory.store, transfer);
    var t = task(); var op = ops.plan(job, "DELETE", old, old, t);
    Files.writeString(b.resolve("x"), "bad");
    try (Storage as = new LocalStorage(a); Storage bs = new LocalStorage(b); Storage cs = new LocalStorage(c)) {
      assertThrows(Exception.class, () -> ops.apply(op, job, as, bs, cs, engine.current(job.id), t));
      assertEquals("bad", Files.readString(b.resolve("x")));
      assertFalse(memory.actions.contains("file.remove_intent"));
      assertFalse(memory.actions.contains("version.archived"));
    }
  }

  @Test
  void sourceChangedAfterArchiveVerificationStillCannotBeDeleted() throws Exception {
    Files.writeString(a.resolve("x"), "old"); sync();
    Node old = engine.current(job.id).get("x"); Files.delete(a.resolve("x"));
    var ops = new FileOperations(memory.store, transfer);
    var t = task(); var op = ops.plan(job, "DELETE", old, old, t);
    try (Storage as = new LocalStorage(a); Storage bs = new LocalStorage(b); Storage cs = new LocalStorage(c)) {
      Storage changedAfterReadBack = new TransferTest.Delegate(cs) {
        public java.io.InputStream read(String path, long offset) throws Exception {
          return new java.io.FilterInputStream(super.read(path, offset)) {
            public void close() throws java.io.IOException {
              super.close(); Files.writeString(b.resolve("x"), "bad");
            }
          };
        }
      };
      assertThrows(Exception.class, () -> ops.apply(op, job, as, bs, changedAfterReadBack, engine.current(job.id), t));
      assertEquals("bad", Files.readString(b.resolve("x")));
      assertEquals("old", Files.readString(c.resolve(op.archivePath)));
      assertTrue(memory.actions.contains("version.archived"));
      assertFalse(memory.actions.contains("file.remove_intent"));
    }
  }

  private byte[] treeHash(Path... roots) throws Exception {
    var hash = Ids.digest();
    for (Path r : roots)
      try (var walk = Files.walk(r)) {
        for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
          hash.update(r.relativize(p).toString().getBytes());
          hash.update(Files.readAllBytes(p));
        }
      }
    return hash.digest();
  }
}
