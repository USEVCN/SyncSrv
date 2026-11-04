package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.AgentService.Device;
import com.oadig.syncsrv.shared.Json;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentBindingTest {
  @TempDir Path root;

  @Test
  void savedBindingCannotSilentlyReuseAnotherSourceBaseline() throws Exception {
    MemoryStore memory = new MemoryStore();
    for (String name : List.of("A", "B", "C")) {
      Endpoint endpoint = new Endpoint();
      endpoint.id = name;
      endpoint.root = Files.createDirectory(root.resolve(name)).toString();
      memory.put("endpoint", name, endpoint);
    }
    Device device = new Device();
    device.id = "device";
    device.status = "APPROVED";
    memory.put("agent", device.id, device);
    ConfigurationService config = new ConfigurationService(memory.store);
    Job job = new Job();
    job.name = "bound";
    job.sourceId = "A";
    job.targetId = "B";
    job.archiveId = "C";
    job.discoveryMode = "EVENTS";
    job.agentId = device.id;
    job.agentRoot = root.resolve("A").toString();
    config.saveJob(job, "test");
    assertEquals(1, memory.store.get("agent", device.id, Device.class).configVersion);
    Job changed = Json.read(Json.write(job), Job.class);
    changed.agentRoot = root.resolve("other").toString();
    assertThrows(IllegalArgumentException.class, () -> config.saveJob(changed, "test"));
    assertEquals(job.agentRoot, config.job(job.id).agentRoot);
    job.name = "renamed";
    config.saveJob(job, "test");
    assertEquals(2, memory.store.get("agent", device.id, Device.class).configVersion);
    device.status = "REVOKED";
    memory.put("agent", device.id, device);
    assertThrows(IllegalArgumentException.class, () -> config.saveJob(job, "test"));
  }

  @Test
  void oneDeviceReceivesMultipleIndependentBackupTasks() throws Exception {
    MemoryStore memory = new MemoryStore();
    Device device = new Device();
    device.id = "device";
    device.status = "APPROVED";
    memory.put("agent", device.id, device);
    ConfigurationService config = new ConfigurationService(memory.store);
    var jobs = new java.util.ArrayList<Job>();
    for (int i = 1; i <= 2; i++) {
      for (String role : List.of("B", "C")) {
        Endpoint endpoint = new Endpoint();
        endpoint.id = role + i;
        endpoint.protocol = "SMB";
        endpoint.host = "nas.test";
        endpoint.root = "Backups/" + role + "/task" + i;
        memory.put("endpoint", endpoint.id, endpoint);
      }
      Job job = new Job();
      job.name = "task" + i;
      job.sourceMode = "AGENT";
      job.agentId = device.id;
      job.agentRoot = "D:/Source" + i;
      job.targetId = "B" + i;
      job.archiveId = "C" + i;
      job.intervalSeconds = i * 5;
      job.bandwidthMiB = i * 10;
      job.transferThreads = i * 4;
      job.enabled = true;
      jobs.add(config.saveJob(job, "test"));
    }
    var agents = new com.oadig.syncsrv.modules.discovery.AgentService(memory.store, config);
    var configuration = agents.configuration(device.id);
    assertEquals(2, configuration.roots().size());
    assertEquals(2, configuration.version());
    for (Job job : jobs) {
      var root =
          configuration.roots().stream()
              .filter(r -> r.jobId().equals(job.id))
              .findFirst()
              .orElseThrow();
      assertEquals(job.agentRoot, root.path());
      assertEquals(job.intervalSeconds, root.intervalSeconds());
      assertEquals(job.bandwidthMiB, root.bandwidthMiB());
      assertEquals(job.transferThreads, root.transferThreads());
      assertTrue(root.adaptiveTransfers());
      assertEquals(16,root.maxTransferThreads());
    }
    jobs.get(0).enabled = false;
    jobs.get(0).transferThreads = 17;
    assertThrows(IllegalArgumentException.class, () -> config.saveJob(jobs.get(0), "test"));
    jobs.get(0).transferThreads = 0;
    assertThrows(IllegalArgumentException.class, () -> config.saveJob(jobs.get(0), "test"));
    jobs.get(0).transferThreads = 1;
    assertEquals(4, Json.read("{}", Job.class).transferThreads);
    config.saveJob(jobs.get(0), "test");
    var updated = agents.configuration(device.id);
    assertEquals(2, updated.roots().size());
    assertEquals(1, updated.roots().stream().filter(r -> r.enabled()).count());
    assertEquals(
        jobs.get(1).id,
        updated.roots().stream().filter(r -> r.enabled()).findFirst().orElseThrow().jobId());
  }
  @Test
  void versionChangeIsAuditedAndOlderHeartbeatsRemainCompatible() throws Exception {
    MemoryStore memory = new MemoryStore();
    Device device = new Device();
    device.id = "upgrading";
    device.status = "APPROVED";
    device.version = "0.6.0-dev";
    memory.put("agent", device.id, device);
    var agents = new com.oadig.syncsrv.modules.discovery.AgentService(memory.store, new ConfigurationService(memory.store));
    var upgraded = new com.oadig.syncsrv.modules.discovery.AgentService.Heartbeat(0, 3, 123, 456, 0, 0, 0.0, "watcher", "", List.of(), "0.7.0-dev");
    assertEquals("0.7.0-dev", agents.heartbeat(device.id, upgraded).version);
    assertEquals(List.of("agent.version_changed"), memory.actions);
    agents.heartbeat(device.id, upgraded);
    assertEquals("agent.heartbeat", memory.actions.get(1));
    var legacy = new com.oadig.syncsrv.modules.discovery.AgentService.Heartbeat(0, 3, 123, 456, 0, 0, 0.0, "watcher", "", List.of());
    assertEquals("0.7.0-dev", agents.heartbeat(device.id, legacy).version);
    assertEquals(1, memory.actions.stream().filter("agent.version_changed"::equals).count());
  }

}
