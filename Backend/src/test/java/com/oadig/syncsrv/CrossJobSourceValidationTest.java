package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.AgentService.Device;
import com.oadig.syncsrv.shared.Models.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrossJobSourceValidationTest {
  @Test
  void anotherJobsAgentSourceCannotBeUsedAsBackupDestination() throws Exception {
    validate(true);
  }

  @Test
  void anotherJobsBackupCannotBeUsedAsNewAgentSource() throws Exception {
    validate(false);
  }

  @Test
  void connectionSourceWithEventWatcherAlsoProtectsItsPhysicalDirectory() throws Exception {
    validate(true, true);
    validate(false, true);
  }

  private void validate(boolean targetInsideSource) throws Exception {
    validate(targetInsideSource, false);
  }

  private void validate(boolean targetInsideSource, boolean connectionSource) throws Exception {
    MemoryStore memory = new MemoryStore();
    ConfigurationService config = new ConfigurationService(memory.store);
    for (String id : List.of("first", "second", "nas")) {
      Device device = new Device(); device.id = id; device.status = "APPROVED";
      memory.put("agent", id, device);
    }
    for (String id : List.of("b1", "c1", "b2", "c2")) {
      Endpoint endpoint = new Endpoint(); endpoint.id = id; endpoint.protocol = "SMB";
      endpoint.host = "nas.test"; endpoint.root = "share/" + id;
      endpoint.agentId = "nas"; endpoint.agentRoot = "/volume1/" + id;
      if (targetInsideSource && id.equals("b2")) {
        endpoint.agentId = "first"; endpoint.agentRoot = "/source/backup";
      }
      memory.put("endpoint", id, endpoint);
    }
    Job first = new Job(); first.name = "first"; first.sourceMode = "AGENT";
    first.agentId = "first"; first.agentRoot = "/source";
    first.targetId = "b1"; first.archiveId = "c1";
    if (connectionSource && targetInsideSource) useConnectionSource(memory, first);
    config.saveJob(first, "test");
    Job second = new Job(); second.name = "second"; second.sourceMode = "AGENT";
    second.agentId = targetInsideSource ? "second" : "nas";
    second.agentRoot = targetInsideSource ? "/source" : "/volume1/b1/child";
    second.targetId = "b2"; second.archiveId = "c2";
    if (connectionSource && !targetInsideSource) useConnectionSource(memory, second);
    assertThrows(IllegalArgumentException.class, () -> config.saveJob(second, "test"));
    assertEquals(1, config.jobs().size());
    second.agentId = "second"; second.agentRoot = "/safe-source";
    Endpoint corrected = config.endpoint("b2");
    corrected.agentId = "nas"; corrected.agentRoot = "/volume1/b2";
    memory.put("endpoint", corrected.id, corrected);
    assertDoesNotThrow(() -> config.saveJob(second, "test"));
  }

  private void useConnectionSource(MemoryStore memory, Job job) {
    Endpoint source = new Endpoint(); source.id = "source";
    source.protocol = "FTP"; source.host = "source.test"; source.root = "/source";
    memory.put("endpoint", source.id, source);
    job.sourceMode = "CONNECTION"; job.discoveryMode = "EVENTS"; job.sourceId = source.id;
  }
}
