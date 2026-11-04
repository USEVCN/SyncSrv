package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;

import com.oadig.syncsrv.modules.transfer.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RunMetricsTest {
  @Test
  void phasesAreExclusiveAndLiveReportsDoNotDoubleCountTime() {
    AtomicLong clock = new AtomicLong();
    RunMetrics m = new RunMetrics(clock::get);
    assertEquals(-1, m.snapshot().workerCpuMillis());
    m.start();
    clock.addAndGet(10_000_000);
    m.stage("HASHING");
    m.read(5_000_000, 4096, false);
    clock.addAndGet(20_000_000);
    assertEquals(30, m.snapshot().elapsedMillis());
    assertEquals(30, m.snapshot().stages().stream().mapToLong(RunMetrics.Stage::millis).sum());
    m.snapshot();
    m.stage("ARCHIVING");
    clock.addAndGet(40_000_000);
    m.finish();
    clock.addAndGet(500_000_000);
    var report = m.snapshot();
    assertEquals(70, report.elapsedMillis());
    assertEquals(70, report.stages().stream().mapToLong(RunMetrics.Stage::millis).sum());
    assertEquals(
        4096, report.stages().stream().mapToLong(RunMetrics.Stage::verificationBytes).sum());
    assertEquals(0, report.stages().stream().mapToLong(RunMetrics.Stage::transferBytes).sum());
  }

  @Test
  void tracksActiveFileAndBoundedSlowestOperationsAndResumeCounters() {
    AtomicLong clock = new AtomicLong();
    RunMetrics m = new RunMetrics(clock::get);
    m.start();
    try (var op = m.operation("large.iso", "DELETE")) {
      clock.addAndGet(3_000_000);
      m.read(2_000_000, 123, true);
      m.resumed(888);
      m.restarted();
      assertEquals("RUNNING", m.snapshot().slowestOperations().get(0).status());
      op.failed(new IOException("network reset"), false);
    }
    assertEquals("FAILED", m.snapshot().slowestOperations().get(0).status());
    for (int i = 0; i < 100; i++)
      try (var op = m.operation("file" + i, "WRITE")) {
        clock.addAndGet(1_000_000);
      }
    var report = m.snapshot();
    assertEquals(20, report.slowestOperations().size());
    assertEquals("large.iso", report.slowestOperations().get(0).path());
    assertEquals(888, report.resumedBytes());
    assertEquals(1, report.resumeCount());
    assertEquals(1, report.checkpointRestarts());
  }
}
