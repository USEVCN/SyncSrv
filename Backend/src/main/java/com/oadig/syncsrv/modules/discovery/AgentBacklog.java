package com.oadig.syncsrv.modules.discovery;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Models.Job;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Source-side work only. READY/CENTER_BACKUP belong to the center's durable inbox. */
public final class AgentBacklog {
  public record Summary(int knownPaths, int waiting, int running, int overdue, String oldestWaitingAt) {}
  public record Row(SourceChanges.Entry change, String jobName, boolean enabled, String eligibleAt, boolean overdue) {}
  public record View(List<Row> entries, int total, int page, int pageSize, Summary summary) {}
  private final Store store;
  private final ConfigurationService config;
  private final Clock clock;
  public AgentBacklog(Store store, ConfigurationService config) { this(store, config, Clock.systemUTC()); }
  public AgentBacklog(Store store, ConfigurationService config, Clock clock) {
    this.store = store; this.config = config; this.clock = clock;
  }
  private List<Row> rows() throws Exception {
    Map<String, Job> jobs = config.jobs().stream().collect(Collectors.toMap(j -> j.id, Function.identity()));
    List<SourceChanges.Entry> pending = store.list("source-change", SourceChanges.Entry.class).stream()
        .filter(e -> jobs.containsKey(e.jobId) && Objects.equals(jobs.get(e.jobId).agentId, e.agentId))
        .filter(e -> List.of("WAITING", "RUNNING", "FAILED").contains(e.status) && !"CENTER_BACKUP".equals(e.phase))
        .toList();
    Map<String, Instant> quietUntil = new HashMap<>();
    for (var entry : pending) if ("WAITING".equals(entry.status)) {
      Instant due = Instant.parse(entry.receivedAt).plusSeconds(jobs.get(entry.jobId).intervalSeconds);
      quietUntil.merge(entry.jobId, due, (a, b) -> a.isAfter(b) ? a : b);
    }
    return pending.stream().map(entry -> {
      Job job = jobs.get(entry.jobId);
      Instant due = Instant.parse(entry.receivedAt).plusSeconds(job.intervalSeconds);
      if (quietUntil.getOrDefault(entry.jobId, due).isAfter(due)) due = quietUntil.get(entry.jobId);
      boolean running = "RUNNING".equals(entry.status);
      boolean overdue = job.enabled && !running && !clock.instant().isBefore(due.plusSeconds(300));
      return new Row(entry, job.name, job.enabled, running ? "" : due.toString(), overdue);
    }).sorted(Comparator.comparing(r -> r.change().receivedAt)).toList();
  }
  private Summary summarize(List<Row> rows) {
    List<Row> waiting = rows.stream().filter(r -> !"RUNNING".equals(r.change().status)).toList();
    return new Summary(rows.size(), waiting.size(), rows.size() - waiting.size(),
        (int) rows.stream().filter(Row::overdue).count(), waiting.isEmpty() ? "" : waiting.get(0).change().receivedAt);
  }
  public Map<String, Summary> summaries() throws Exception {
    Map<String, List<Row>> grouped = rows().stream().collect(Collectors.groupingBy(r -> r.change().agentId));
    Map<String, Summary> result = new HashMap<>();
    grouped.forEach((agent, rows) -> result.put(agent, summarize(rows)));
    return result;
  }
  public View view(String agent, int page) throws Exception {
    if (page < 1 || page > 1_000_000) throw new IllegalArgumentException("页码不正确");
    List<Row> rows = rows().stream().filter(r -> Objects.equals(r.change().agentId, agent)).toList();
    return new View(rows.stream().skip((long) (page - 1) * 50).limit(50).toList(), rows.size(), page, 50, summarize(rows));
  }
}
