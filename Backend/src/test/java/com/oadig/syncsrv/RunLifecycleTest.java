package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.config.*;
import com.oadig.syncsrv.modules.jobs.*;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class RunLifecycleTest {
  @Test
  void pauseHoldsSchedulerAndManualExecutionCreatesDurableReport() throws Exception {
    MemoryStore memory = new MemoryStore();
    ConfigurationService config = mock(ConfigurationService.class);
    SyncEngine sync = mock(SyncEngine.class);
    Job job = new Job();
    job.id = "job";
    job.enabled = true;
    when(config.jobs()).thenReturn(List.of(job));
    when(config.job("job")).thenReturn(job);
    when(config.endpoint(anyString())).thenReturn(new Endpoint());
    Run previous = new Run();
    previous.id = "paused";
    previous.jobId = "job";
    previous.status = "PAUSED";
    previous.startedAt = Instant.now().minusSeconds(120).toString();
    previous.completedAt = Ids.now();
    memory.put("run", previous.id, previous);
    try (TaskManager tasks =
        new TaskManager(memory.store, config, sync, mock(RestoreService.class))) {
      var tick = TaskManager.class.getDeclaredMethod("tick");
      tick.setAccessible(true);
      tick.invoke(tasks);
      assertEquals(1, tasks.runs().size());
      verifyNoInteractions(sync);
      doAnswer(call -> {
        com.oadig.syncsrv.modules.transfer.TaskContext t=call.getArgument(1);
        for(int i=0;i<103;i++)t.progress.plan("f"+i,"WRITE",10);
        t.progress.planned();
        for(int i=0;i<103;i++){t.progress.begin("f"+i,"WRITE",10);t.done();}
        return null;
      }).when(sync).sync(eq(job),any(com.oadig.syncsrv.modules.transfer.TaskContext.class));
      Run submitted = tasks.submit("job", "test");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (tasks.busy() && System.nanoTime() < deadline) Thread.sleep(10);
      assertFalse(tasks.busy());
      var report = tasks.report(submitted.id);
      assertEquals("MEASURED", report.coverage());
      assertEquals("COMPLETED", report.run().status);
      assertNotNull(report.metrics());
      assertTrue(memory.actions.contains("run.completed"));
      assertNotNull(memory.store.get("run-report", submitted.id, RunReports.Report.class));
      assertEquals(103,report.run().progress.processedFiles());
      assertEquals(1030,report.run().progress.processedBytes());
      assertEquals(3,tasks.files(submitted.id,2).entries().size());
      assertTrue(tasks.files(submitted.id,2).entries().stream().allMatch(f->f.status().equals("COMPLETED")));
      assertEquals("LEGACY", tasks.files("paused",1).coverage());
      assertEquals("LEGACY", tasks.report("paused").coverage());
    }
  }

  @Test
  void policyEditingNoLongerWaitsForUnrelatedRunningBackup() throws Exception {
    ConfigurationService config = mock(ConfigurationService.class);
    TaskManager tasks = mock(TaskManager.class);
    when(tasks.busy()).thenReturn(true);
    Job job = new Job();
    job.id = "job";
    job.intervalSeconds = 60;
    var request = mock(jakarta.servlet.http.HttpServletRequest.class);
    when(request.getAttribute("actor")).thenReturn("admin");
    when(config.saveJob(job, "admin")).thenReturn(job);
    new ConfigurationController(config, tasks).job(job, request);
    verify(config).saveJob(job, "admin");
  }
}
