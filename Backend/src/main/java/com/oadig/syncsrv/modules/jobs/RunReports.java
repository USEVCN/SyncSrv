package com.oadig.syncsrv.modules.jobs;

import com.oadig.syncsrv.modules.transfer.RunMetrics;
import com.oadig.syncsrv.shared.Ids;
import com.oadig.syncsrv.shared.Models.*;
import java.time.*;

public final class RunReports {
  private RunReports() {}

  public record Configuration(
      double globalLimitMiB,
      double taskLimitMiB,
      String sourceMode,
      String sourceProtocol,
      String targetProtocol,
      String archiveProtocol) {}

  public record Report(
      Run run,
      String coverage,
      String generatedAt,
      long queueMillis,
      long wallMillis,
      Configuration configuration,
      RunMetrics.Report metrics) {}

  public static Report create(
      Run run, Configuration configuration, RunMetrics.Report metrics, String coverage) {
    String end = run.completedAt.isBlank() ? Ids.now() : run.completedAt;
    String queued = run.queuedAt.isBlank() ? run.startedAt : run.queuedAt;
    String start = run.actualStartedAt.isBlank() ? queued : run.actualStartedAt;
    return new Report(
        run,
        coverage,
        Ids.now(),
        between(queued, run.actualStartedAt.isBlank() ? end : start),
        between(start, end),
        configuration,
        metrics);
  }

  private static long between(String from, String to) {
    try {
      return Math.max(0, Duration.between(Instant.parse(from), Instant.parse(to)).toMillis());
    } catch (Exception ignored) {
      return 0;
    }
  }
}
