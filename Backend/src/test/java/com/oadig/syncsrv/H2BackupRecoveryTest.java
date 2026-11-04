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

class H2BackupRecoveryTest {
  @TempDir Path root;
  private com.oadig.syncsrv.platform.persistence.Store store;
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
    store = new com.oadig.syncsrv.platform.persistence.Store(root.resolve("db"));
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
    transfer = new TransferService(store);
    engine = new SyncEngine(store, config, transfer);
    restore = new RestoreService(store, config, engine, transfer);
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
        store.list("version", Version.class).stream()
            .anyMatch(v -> v.reason().equals("deleted") && v.location().contains(".__deleted")));
    store.close();
    Path archive = root.resolve("metadata.zip"), recovered = root.resolve("recovered-db");
    com.oadig.syncsrv.platform.persistence.MetadataTools.backup(root.resolve("db"), archive);
    com.oadig.syncsrv.platform.persistence.MetadataTools.restore(archive, recovered);
    store = new com.oadig.syncsrv.platform.persistence.Store(recovered);
    transfer = new TransferService(store); engine = new SyncEngine(store, config, transfer);
    restore = new RestoreService(store, config, engine, transfer);
    assertTrue(store.verify().sequence() > 0);
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

  @AfterEach void closeStore() throws Exception { if(store != null) store.close(); }
}
