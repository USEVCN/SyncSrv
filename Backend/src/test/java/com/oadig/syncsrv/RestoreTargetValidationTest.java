package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.shared.Models.*;
import org.junit.jupiter.api.Test;

class RestoreTargetValidationTest {
  @Test
  void remoteRestoreRejectsBackupOverlapAndPathTraversal() throws Exception {
    MemoryStore memory = new MemoryStore();
    Endpoint b = new Endpoint();
    b.id = "B";
    b.protocol = "SMB";
    b.host = "nas.test";
    b.root = "share/B";
    memory.put("endpoint", b.id, b);
    Endpoint c = new Endpoint();
    c.id = "C";
    c.protocol = "SMB";
    c.host = "nas.test";
    c.root = "share/C";
    memory.put("endpoint", c.id, c);
    Job job = new Job();
    job.id = "job";
    job.targetId = "B";
    job.archiveId = "C";
    memory.put("job", job.id, job);
    ConfigurationService config = new ConfigurationService(memory.store);
    Endpoint d = new Endpoint();
    d.id = "D";
    d.protocol = "SMB";
    d.host = "NAS.TEST";
    d.port = 445;
    d.root = "share";
    assertThrows(Exception.class, () -> config.validateRestoreEndpoint(d, "B/point"));
    assertThrows(Exception.class, () -> config.validateRestoreEndpoint(d, ""));
    assertThrows(Exception.class, () -> config.validateRestoreEndpoint(d, "../escape"));
    assertThrows(Exception.class, () -> config.validateRestoreEndpoint(d, ".syncsrv/control"));
    assertDoesNotThrow(() -> config.validateRestoreEndpoint(d, "D/point"));
    Run run = new Run();
    run.id = "run";
    run.kind = "RESTORE";
    run.restoreEndpointId = "D";
    run.status = "PAUSED";
    memory.put("run", run.id, run);
    memory.put("endpoint", d.id, d);
    assertThrows(Exception.class, () -> config.deleteEndpoint("D", "test"));
  }
}
