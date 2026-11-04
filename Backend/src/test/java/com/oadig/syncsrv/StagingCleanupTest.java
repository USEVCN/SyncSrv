package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.sync.StagingCleanup;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StagingCleanupTest {
  @TempDir Path root;

  @Test
  void removesOnlyAbandonedOperationPartsAndKeepsResumeAndVersionFiles() throws Exception {
    MemoryStore memory = new MemoryStore();
    ConfigurationService config = mock(ConfigurationService.class);
    Endpoint endpoint = new Endpoint();
    endpoint.root = root.toString();
    when(config.endpoint("B")).thenReturn(endpoint);
    Job job = new Job();
    job.id = "job";
    job.targetId = "B";
    Path staging = Files.createDirectories(root.resolve(".syncsrv/staging/job"));
    String orphan = "a".repeat(32) + ".part", live = "b".repeat(32) + ".part";
    Files.writeString(staging.resolve(orphan), "orphan");
    Files.writeString(staging.resolve(live), "resume");
    Files.writeString(staging.resolve("unknown.part"), "unknown");
    Files.writeString(root.resolve("current.txt"), "version");
    Operation op = new Operation();
    op.id = "active";
    op.jobId = "job";
    op.temp = ".syncsrv/staging/job/" + live;
    memory.put("operation", op.id, op);
    var result = new StagingCleanup(memory.store, config).clean(job, "test", "run");
    assertEquals(1, result.files());
    assertEquals(6, result.bytes());
    assertEquals(2, result.retained());
    assertFalse(Files.exists(staging.resolve(orphan)));
    assertEquals("resume", Files.readString(staging.resolve(live)));
    assertTrue(Files.exists(staging.resolve("unknown.part")));
    assertEquals("version", Files.readString(root.resolve("current.txt")));
    assertEquals(
        java.util.List.of("staging.remove_intent", "staging.removed", "staging.cleanup_completed"),
        memory.actions);
  }
}
