package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.ChangeInbox;
import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.modules.transfer.TaskContext;
import com.oadig.syncsrv.shared.Ids;
import com.oadig.syncsrv.shared.Models.Job;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class EventSchedulingTest {
  @Test
  void durableReceptionWakesScopedRunAndRetainsChangeArrivingDuringExecution() throws Exception {
    MemoryStore memory = new MemoryStore();
    ConfigurationService config = mock(ConfigurationService.class);
    SyncEngine sync = mock(SyncEngine.class);
    Job job = new Job();
    job.id = "job";
    job.enabled = true;
    job.discoveryMode = "EVENTS";
    job.intervalSeconds = 1;
    when(config.jobs()).thenReturn(List.of(job));
    when(config.endpoint(anyString())).thenReturn(new com.oadig.syncsrv.shared.Models.Endpoint());
    when(sync.checkpoint(job.id)).thenReturn(new SyncEngine.Checkpoint(Ids.now(), "baseline", 1));
    ChangeInbox inbox = new ChangeInbox(memory.store);
    inbox.receive(
        "source",
        new ChangeInbox.Batch(
            "epoch", List.of(new ChangeInbox.Event("1", "job", "file", "CHANGE", Ids.now()))));
    CountDownLatch completed = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              TaskContext task = invocation.getArgument(1);
              assertEquals("EVENTS", task.run.trigger);
              assertFalse(task.run.actualStartedAt.isEmpty());
              assertEquals(List.of("file"), invocation.getArgument(2));
              inbox.receive(
                  "source",
                  new ChangeInbox.Batch(
                      "epoch",
                      List.of(new ChangeInbox.Event("2", "job", "file", "CHANGE", Ids.now()))));
              completed.countDown();
              return null;
            })
        .when(sync)
        .sync(eq(job), any(TaskContext.class), anyCollection());
    try (TaskManager tasks =
        new TaskManager(memory.store, config, sync, mock(RestoreService.class))) {
      tasks.changesArrived();
      assertTrue(completed.await(3, TimeUnit.SECONDS), "event did not wake execution");
    }
    assertEquals(1, inbox.pending(job.id).size());
    verify(sync, never()).sync(any(Job.class), any(TaskContext.class));
  }
}
