package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.discovery.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.storage.Storage;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DeviceExecutionConcurrencyTest {
  @TempDir Path root;
  MemoryStore memory;
  DeviceExecution execution;
  final String agent = "a".repeat(32);

  @BeforeEach void init() throws Exception {
    memory = new MemoryStore(); execution = new DeviceExecution(memory.store);
    when(memory.store.encrypt(anyString())).thenAnswer(i -> i.getArgument(0));
    when(memory.store.decrypt(anyString())).thenAnswer(i -> i.getArgument(0));
    AgentService.Device device = new AgentService.Device(); device.id = agent; device.status = "APPROVED";
    memory.put("agent", agent, device);
  }

  DeviceExecution.Command command(int index, String status) {
    var c = new DeviceExecution.Command();
    c.id = String.format("%032x", index); c.agentId = agent; c.status = status;
    c.deadline = System.currentTimeMillis() + 15000;
    memory.put("device-pending", c.id, c); memory.put(DeviceExecution.KIND, c.id, c);
    return c;
  }

  @Test void activeCommandsDoNotBlockIndependentClaimsAndLegacyPollStillReplays() throws Exception {
    var first = command(1, "QUEUED"); var second = command(2, "QUEUED");
    assertEquals(first.id, execution.poll(agent).id);
    assertEquals(first.id, execution.poll(agent).id, "legacy clients must reconcile an unacknowledged command");
    assertEquals(second.id, execution.poll(agent, Set.of(first.id)).id);
    assertNull(execution.poll(agent, Set.of(first.id, second.id)));
    assertEquals("RUNNING", memory.store.get(DeviceExecution.KIND, second.id, DeviceExecution.Command.class).status);
    assertEquals(2, memory.actions.stream().filter("device.claimed"::equals).count());
  }

  @Test void restartAndCancellationRemainReplayable() throws Exception {
    var cancelling = command(1, "CANCEL_REQUESTED"); var queued = command(2, "QUEUED");
    assertEquals(cancelling.id, execution.poll(agent).id);
    assertEquals(queued.id, execution.poll(agent, Set.of(cancelling.id)).id);
    assertEquals(cancelling.id, new DeviceExecution(memory.store).poll(agent).id);
  }

  @Test void busyDeviceMayClaimWorkQueuedLongerThanDeliveryWindow() throws Exception {
    var first = command(1, "RUNNING"); execution.poll(agent);
    var second = command(2, "QUEUED"); second.deadline = System.currentTimeMillis() - 100;
    memory.put("device-pending", second.id, second); memory.put(DeviceExecution.KIND, second.id, second);
    var claimed = execution.poll(agent, Set.of(first.id));
    assertNotNull(claimed); assertEquals(second.id, claimed.id);
    assertEquals("RUNNING", claimed.status); assertTrue(claimed.deadline > System.currentTimeMillis());
    assertFalse(memory.actions.contains("device.not_started"));
  }

  @Test void idleUnclaimedTimeoutStillExpiresAndOtherDeviceCommandsNeverLeak() throws Exception {
    var expired = command(1, "QUEUED"); expired.deadline = 0;
    memory.put("device-pending", expired.id, expired); memory.put(DeviceExecution.KIND, expired.id, expired);
    var other = command(2, "RUNNING"); other.agentId = "b".repeat(32);
    memory.put("device-pending", other.id, other);
    assertNull(execution.poll(agent));
    assertEquals("NOT_STARTED", memory.store.get(DeviceExecution.KIND, expired.id, DeviceExecution.Command.class).status);
  }

  @Test void coordinatorRenewsQueuedWorkWhileDeviceSlotsAreBusy() throws Exception {
    command(1, "RUNNING"); execution.poll(agent);
    Endpoint endpoint = new Endpoint(); endpoint.id = Ids.next(); endpoint.root = root.toString();
    endpoint.agentId = agent; endpoint.agentRoot = root.toString();
    Run run = new Run(); run.id = Ids.next(); run.actor = "test";
    TaskContext task = new TaskContext(run, new BandwidthLimiter(0), 0);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Boolean> future = pool.submit(() -> execution.copy(new Storage.Location(endpoint, "source"),
          new Storage.Location(endpoint, "target"), 4, Ids.hash(new byte[4]), task));
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      DeviceExecution.Command queued = null;
      while (queued == null && System.nanoTime() < until) {
        queued = memory.store.list(DeviceExecution.KIND, DeviceExecution.Command.class).stream()
            .filter(c -> c.status.equals("QUEUED")).findFirst().orElse(null);
        if (queued == null) Thread.sleep(5);
      }
      assertNotNull(queued);
      queued.deadline = 0;
      memory.put(DeviceExecution.KIND, queued.id, queued); memory.put("device-pending", queued.id, queued);
      while (!memory.actions.contains("device.queue_wait") && System.nanoTime() < until) Thread.sleep(5);
      assertTrue(memory.actions.contains("device.queue_wait"));
      assertFalse(future.isDone(), "a busy device must not cause automatic relay");
      assertEquals("QUEUED", memory.store.get(DeviceExecution.KIND, queued.id, DeviceExecution.Command.class).status);
      task.cancelled.set(true);
      assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    } finally { task.cancelled.set(true); pool.shutdownNow(); }
  }

  @Test void activeIdsAreBoundedAndOldJsonRemainsCompatible() {
    var old = Json.read("{\"publicKey\":\"key\",\"requestId\":\"id\"}", DeviceExecution.Poll.class);
    assertTrue(DeviceExecution.activeIds(old.activeIds()).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> DeviceExecution.activeIds(List.of("bad")));
    assertThrows(IllegalArgumentException.class, () -> DeviceExecution.activeIds(List.of(agent, agent)));
    assertThrows(IllegalArgumentException.class, () -> DeviceExecution.activeIds(
        java.util.stream.IntStream.range(0, 17).mapToObj(i -> String.format("%032x", i)).toList()));
  }

  @Test void matchingBindingIsNotTruncatedAndParallelPublicationIsStable() throws Exception {
    Endpoint endpoint = new Endpoint(); endpoint.id = Ids.next(); endpoint.root = root.toString();
    endpoint.agentId = agent; endpoint.agentRoot = root.toString();
    var initial = execution.bind(endpoint, agent);
    Path marker = root.resolve(initial.marker()); byte[] content = Files.readAllBytes(marker);
    FileTime sentinel = FileTime.fromMillis(123456000); Files.setLastModifiedTime(marker, sentinel);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<DeviceExecution.BoundEndpoint>> futures = new ArrayList<>();
      for (int i = 0; i < 16; i++) futures.add(pool.submit(() -> execution.bind(endpoint, agent)));
      for (var future : futures) assertEquals(initial.markerHash(), future.get(5, TimeUnit.SECONDS).markerHash());
    } finally { pool.shutdownNow(); }
    assertArrayEquals(content, Files.readAllBytes(marker));
    assertEquals(sentinel, Files.getLastModifiedTime(marker), "valid challenge must not be rewritten under readers");
  }
}
