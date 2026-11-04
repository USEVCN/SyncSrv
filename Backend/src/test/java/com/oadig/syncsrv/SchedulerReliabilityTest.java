package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.modules.transfer.TaskContext;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Models.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SchedulerReliabilityTest {
  private final MemoryStore memory = new MemoryStore();
  private final ConfigurationService config = mock(ConfigurationService.class);
  private final SyncEngine sync = mock(SyncEngine.class);

  SchedulerReliabilityTest() throws Exception {
    when(config.endpoint(anyString())).thenReturn(new Endpoint());
    when(config.jobs()).thenReturn(List.of());
  }

  private Job job(String id) throws Exception {
    Job job = new Job(); job.id = id; job.enabled = true;
    when(config.job(id)).thenReturn(job);
    return job;
  }

  private TaskManager manager() throws Exception {
    return new TaskManager(memory.store, config, sync, mock(RestoreService.class));
  }

  private static void tick(TaskManager tasks) throws Exception {
    var method = TaskManager.class.getDeclaredMethod("tick");
    method.setAccessible(true); method.invoke(tasks);
  }

  private static void awaitIdle(TaskManager tasks) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (tasks.busy() && System.nanoTime() < deadline) Thread.sleep(10);
    assertFalse(tasks.busy(), "run did not reach durable terminal state");
  }

  @Test
  void queuedPauseCompletesImmediatelyAndNeverRunsAfterBlockingJob() throws Exception {
    Job first = job("first"), second = job("second");
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    doAnswer(call -> { entered.countDown(); release.await(); return null; })
        .when(sync).sync(eq(first), any(TaskContext.class));
    try (TaskManager tasks = manager()) {
      tasks.submit(first.id, "test");
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      Run waiting = tasks.submit(second.id, "test");
      tasks.pause(waiting.id, "test");
      Run saved = memory.store.get("run", waiting.id, Run.class);
      assertEquals("PAUSED", saved.status);
      assertEquals("", saved.actualStartedAt);
      assertFalse(saved.completedAt.isBlank());
      assertEquals(1, tasks.liveRuns().size());
      assertThrows(IllegalArgumentException.class, () -> tasks.pause(waiting.id, "test"));
      release.countDown(); awaitIdle(tasks);
      verify(sync, never()).sync(eq(second), any(TaskContext.class));
    } finally { release.countDown(); }
  }

  @Test
  void finalReportFailureStaysVisibleAndBlocksResubmissionUntilSaved() throws Exception {
    Job job = job("job");
    AtomicBoolean offline = new AtomicBoolean(true);
    CountDownLatch saveFailed = new CountDownLatch(1);
    doAnswer(call -> {
      if (offline.get()) { saveFailed.countDown(); throw new IOException("database offline"); }
      memory.actions.add(call.getArgument(1));
      for (Object value : call.getArguments())
        if (value instanceof Store.Change change) memory.put(change.kind(), change.id(), change.value());
      return null;
    }).when(memory.store).commit(anyString(), eq("run.completed"), anyString(), any(), any(Store.Change[].class));
    try (TaskManager tasks = manager()) {
      Run run = tasks.submit(job.id, "test");
      assertTrue(saveFailed.await(3, TimeUnit.SECONDS));
      assertTrue(tasks.busy());
      assertEquals("COMPLETED", tasks.liveRuns().get(0).status);
      assertThrows(IllegalArgumentException.class, () -> tasks.submit(job.id, "test"));
      offline.set(false); tick(tasks); awaitIdle(tasks);
      assertEquals("COMPLETED", memory.store.get("run", run.id, Run.class).status);
      assertEquals("COMPLETED", tasks.report(run.id).run().status);
      assertTrue(memory.actions.contains("run.completed"));
    } finally { offline.set(false); }
  }

  @Test
  void oneInvalidJobDoesNotBlockHealthyJobsAndTicksDoNotReloadHistory() throws Exception {
    Job broken = job("broken"), healthy = job("healthy");
    broken.discoveryMode = "EVENTS";
    when(config.jobs()).thenReturn(List.of(broken, healthy));
    when(sync.checkpoint(broken.id)).thenThrow(new IOException("bad checkpoint"));
    try (TaskManager tasks = manager()) {
      tick(tasks); awaitIdle(tasks); tick(tasks);
      verify(sync).sync(eq(healthy), any(TaskContext.class));
      verify(memory.store, times(1)).list("run", Run.class);
    }
  }

  @Test
  void shutdownPersistsInterruptionsAndEnabledJobsResumeAfterRestart() throws Exception {
    Job first = job("first"); job("second"); job("third");
    CountDownLatch entered = new CountDownLatch(1);
    doAnswer(call -> { entered.countDown(); new CountDownLatch(1).await(); return null; })
        .when(sync).sync(eq(first), any(TaskContext.class));
    TaskManager tasks = manager();
    try {
      tasks.submit("first", "test"); assertTrue(entered.await(3, TimeUnit.SECONDS));
      Run second = tasks.submit("second", "test"), third = tasks.submit("third", "test");
      tasks.close();
      assertFalse(tasks.busy());
      for (Run run : List.of(second, third)) {
        Run saved = memory.store.get("run", run.id, Run.class);
        assertEquals("INTERRUPTED", saved.status);
        assertEquals("", saved.actualStartedAt);
        assertFalse(saved.completedAt.isBlank());
      }
      assertTrue(memory.store.list("run", Run.class).stream().allMatch(r -> r.status.equals("INTERRUPTED")));
    } finally { tasks.close(); }
    for (Run run : memory.store.list("run", Run.class)) {
      run.startedAt = run.completedAt = java.time.Instant.now().minusSeconds(600).toString();
      memory.put("run", run.id, run);
    }
    List<Job> jobs = List.of(first, config.job("second"), config.job("third"));
    when(config.jobs()).thenReturn(jobs);
    reset(sync);
    try (TaskManager restarted = manager()) {
      tick(restarted); awaitIdle(restarted);
      verify(sync, times(3)).sync(any(Job.class), any(TaskContext.class));
    }
  }
}
