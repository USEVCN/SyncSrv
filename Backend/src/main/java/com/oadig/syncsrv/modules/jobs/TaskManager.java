package com.oadig.syncsrv.modules.jobs;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.modules.archive.ArchiveService;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.ChangeInbox;
import com.oadig.syncsrv.modules.discovery.SourceChanges;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** One writer across sync and recovery keeps version locations stable while a restore runs. */
public final class TaskManager implements AutoCloseable {
  public record Settings(double bandwidthMiB) {}

  private final Store store;
  private final ConfigurationService config;
  private final SyncEngine sync;
  private final ChangeInbox inbox;
  private final RestoreService restore;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "syncsrv-worker"));
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "syncsrv-scheduler"));
  private final ConcurrentMap<String, TaskContext> active = new ConcurrentHashMap<>();
  public final BandwidthLimiter limiter;
  private volatile boolean closing;
  private final ConcurrentMap<String, RunReports.Configuration> runConfigurations =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Long> reportSavedAt = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Run> latestBackups = new ConcurrentHashMap<>();
  private final Set<String> shutdownRuns = ConcurrentHashMap.newKeySet();
  private final java.util.concurrent.atomic.AtomicBoolean eventWakePending =
      new java.util.concurrent.atomic.AtomicBoolean();

  public TaskManager(
      Store store, ConfigurationService config, SyncEngine sync, RestoreService restore)
      throws Exception {
    this.store = store;
    this.config = config;
    this.sync = sync;
    this.inbox = new ChangeInbox(store);
    this.restore = restore;
    Settings settings = store.get("settings", "global", Settings.class);
    limiter = new BandwidthLimiter(settings == null ? 0 : settings.bandwidthMiB());
    for (Run r : store.runHeads()) {
      if (List.of("RUNNING", "QUEUED", "PAUSING").contains(r.status)) {
        r.status = "INTERRUPTED";
        r.completedAt = Ids.now();
        r.error = "服务曾中断，可重新执行以复用已验证断点";
        RunReports.Report previous = store.get("run-report", r.id, RunReports.Report.class);
        if (previous == null) {
          store.commit(
              "system",
              "run.interrupted",
              r.id,
              Map.of("reason", "SERVICE_RESTART"),
              put("run", r.id, r));
        } else {
          RunReports.Report partial =
              RunReports.create(r, previous.configuration(), previous.metrics(), "PARTIAL");
          store.commit(
              "system",
              "run.interrupted",
              r.id,
              Map.of("reason", "SERVICE_RESTART", "reportHash", Ids.hash(Json.bytes(partial))),
              put("run", r.id, r),
              put("run-report", r.id, partial));
        }
      }
      remember(r);
    }
  }

  public void start() {
    scheduler.scheduleWithFixedDelay(this::tick, 5, 5, TimeUnit.SECONDS);
  }

  /** Durable inbox commits wake the scheduler; the periodic tick repairs missed wakeups. */
  public void changesArrived() {
    if (closing || !eventWakePending.compareAndSet(false, true)) return;
    try {
      scheduler.schedule(
          () -> {
            eventWakePending.set(false);
            tick();
          },
          100,
          TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      eventWakePending.set(false);
      if (!closing) throw e;
    }
  }

  public Map<String,Object> bandwidth(int count) {
    return Map.of("waitMillis", limiter.reserve(count));
  }

  public Settings settings() {
    return new Settings(limiter.mib());
  }

  public void settings(Settings settings, String actor) throws Exception {
    BandwidthLimiter check = new BandwidthLimiter(settings.bandwidthMiB());
    store.commit(actor, "settings.saved", "global", settings, put("settings", "global", settings));
    limiter.set(check.mib());
  }

  public List<Run> runs() throws Exception {
    Map<String, Run> result = new HashMap<>();
    for (Run r : store.recent("run", 200, Run.class)) result.put(r.id, r);
    for (Run r : latestBackups.values()) result.putIfAbsent(r.id,r);
    for (TaskContext t : active.values())
      synchronized (t.run) {
        Run copy = Json.read(Json.write(t.run), Run.class);
        copy.progress = t.progress.snapshot(t.run);
        result.put(t.run.id, copy);
      }
    return result.values().stream()
        .sorted(Comparator.comparing((Run r) -> r.startedAt).reversed())
        .toList();
  }

  public List<Run> liveRuns() {
    return active.values().stream().map(task -> {
      synchronized(task.run) {
        Run copy=Json.read(Json.write(task.run),Run.class);
        copy.progress=task.progress.snapshot(task.run);
        return copy;
      }
    }).toList();
  }

  public synchronized Run submit(String jobId, String actor) throws Exception {
    if(store.get("recovery-state","pending",Map.class)!=null)throw new IllegalArgumentException("请先完成备份库与归档库灾备校验");
    Job j = config.job(jobId);
    Run r = new Run();
    r.jobId = jobId;
    r.kind = "SYNC";
    return enqueue(r, j, actor);
  }

  public synchronized Run restore(String snapshotId, String destination, String actor)
      throws Exception {
    return restore(snapshotId, "", destination, actor);
  }

  public synchronized Run restore(
      String snapshotId, String endpointId, String destination, String actor) throws Exception {
    Snapshot s = sync.snapshot(snapshotId);
    if (endpointId == null || endpointId.isBlank())
      config.validateRestore(java.nio.file.Path.of(destination));
    else
      config.validateRestoreEndpoint(
          config.endpoint(endpointId), destination == null ? "" : destination);
    Run r = new Run();
    r.kind = "RESTORE";
    r.jobId = s.jobId();
    r.snapshotId = snapshotId;
    r.target = destination == null ? "" : destination;
    r.restoreEndpointId = endpointId == null ? "" : endpointId;
    return enqueue(r, config.job(s.jobId()), actor);
  }

  public synchronized Run archive(String jobId, String planId, boolean confirmed, String actor)
      throws Exception {
    if(store.get("recovery-state","pending",Map.class)!=null)throw new IllegalArgumentException("请先完成备份库与归档库灾备校验");
    if (closing || active.values().stream().anyMatch(t -> t.run.jobId.equals(jobId)))
      throw new IllegalArgumentException("请等待该任务的备份、合成或整理结束后提交");
    new ArchiveService(store, config).confirm(jobId, planId, confirmed, actor);
    Run run = new Run(); run.jobId = jobId; run.kind = "ARCHIVE_CLEANUP"; run.target = planId;
    return enqueue(run, config.job(jobId), actor);
  }

  private synchronized Run enqueue(Run r, Job j, String actor) throws Exception {
    if (closing) throw new IllegalStateException("服务正在停止");
    if (active.values().stream().anyMatch(t -> t.run.jobId.equals(j.id)))
      throw new IllegalArgumentException("该任务已有执行或恢复排队中");
    if (active.size() >= 32) throw new IllegalArgumentException("队列已满，请稍后再试");
    r.id = Ids.next();
    r.actor = actor;
    r.startedAt = Ids.now();
    r.queuedAt = r.startedAt;
    TaskContext task = new TaskContext(r, limiter, j.bandwidthMiB);
    RunReports.Configuration configuration = new RunReports.Configuration(
            limiter.mib(),
            j.bandwidthMiB,
            j.sourceMode,
            j.sourceMode.equals("AGENT") ? "AGENT_DIRECT" : config.endpoint(j.sourceId).protocol,
            config.endpoint(j.targetId).protocol,
            config.endpoint(j.archiveId).protocol);
    store.commit(actor, "run.queued", r.id, r, put("run", r.id, r));
    runConfigurations.put(r.id, configuration);
    reportSavedAt.put(r.id, System.nanoTime());
    active.put(r.id, task);
    remember(r);
    worker.submit(() -> execute(task, j));
    return r;
  }

  private void execute(TaskContext task, Job job) {
    Run r = task.run;
    ChangeInbox.Claim claim = null;
    SourceChanges visibility = new SourceChanges(store, config);
    List<SourceChanges.Entry> visibleClaim = List.of();
    try {
      synchronized (task) {
        // A queued task may already have been paused and durably closed.
        if (!r.completedAt.isBlank()) return;
        task.thread = Thread.currentThread();
        task.check();
        task.metrics.start();
        task.progress.start();
        r.status = "RUNNING";
        r.actualStartedAt = Ids.now();
        store.commit(r.actor, "run.started", r.id, r, put("run", r.id, r));
      }
      if (r.kind.equals("SYNC")) {
        claim = inbox.claim(job.id);
        visibleClaim = visibility.claim(claim, r.id);
        if (r.trigger.equals("EVENTS")) sync.sync(job, task, claim.paths());
        else sync.sync(job, task);
        visibility.finish(visibleClaim, "");
        inbox.completed(claim, r.id);
      } else if (r.kind.equals("ARCHIVE_CLEANUP")) {
        new ArchiveService(store, config).execute(job.id, r.target, task);
      } else restore.restore(r.snapshotId, r.restoreEndpointId, r.target, task);
      synchronized (task) {
        task.check();
        r.status = "COMPLETED";
        task.stage("COMPLETE", "");
      }
    } catch (Exception e) {
      boolean paused = task.cancelled.get() || Thread.currentThread().isInterrupted();
      synchronized (task) {
        r.status = paused ? shutdownRuns.contains(r.id) ? "INTERRUPTED" : "PAUSED" : "FAILED";
        r.error = shutdownRuns.contains(r.id) ? "服务停止，恢复后自动重试并复用可验证断点"
            : paused ? "已暂停，重新执行会复用可验证断点" : safeMessage(e);
      }
      if (claim != null) {
        try {
          inbox.failed(claim, r.error, r.id);
          visibility.finish(visibleClaim, r.error);
        } catch (Exception persist) {
          org.slf4j.LoggerFactory.getLogger(TaskManager.class)
              .error("Persist retry failed", persist);
        }
      }
      if (!paused)
        org.slf4j.LoggerFactory.getLogger(TaskManager.class).warn("Task {} failed", r.id, e);
    } finally {
      Thread.interrupted();
      synchronized (task) {
        if (r.completedAt.isBlank()) {
          r.completedAt = Ids.now();
          task.metrics.finish();
          task.progress.finish(r);
        }
        task.thread = null;
      }
      try {
        persistCompleted(task);
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(TaskManager.class)
            .error("Persist task completion failed", e);
      }
    }
  }

  public void pause(String id, String actor) throws Exception {
    pause(id, actor, false);
  }

  private void pause(String id, String actor, boolean shutdown) throws Exception {
    TaskContext t = ConfigurationService.require(active.get(id), "任务已结束");
    synchronized (t) {
      if (!List.of("QUEUED", "RUNNING", "PAUSING").contains(t.run.status))
        throw new IllegalArgumentException("任务已结束");
      if (t.run.status.equals("PAUSING")) return;
      store.commit(actor, "run.pause_requested", id, Map.of());
      if (shutdown) shutdownRuns.add(id);
      t.cancelled.set(true);
      if (t.thread != null) {
        t.run.status = "PAUSING";
        t.thread.interrupt();
      } else {
        t.run.status = shutdown ? "INTERRUPTED" : "PAUSED";
        t.run.error = shutdown ? "服务停止，尚未执行；恢复后自动重试" : "已取消排队，尚未开始执行";
        t.run.completedAt = Ids.now();
        t.progress.finish(t.run);
        persistCompleted(t);
      }
    }
  }

  private void remember(Run run) {
    if (run.kind.equals("SYNC")) latestBackups.compute(run.jobId, (id, old) ->
        old == null || old.startedAt.compareTo(run.startedAt) <= 0 ? run : old);
  }

  /** Keep terminal runs visible and block new writes until their report is durable. */
  private void persistCompleted(TaskContext task) throws Exception {
    synchronized (task) {
      if (!active.containsKey(task.run.id)) return;
      persistReport(task, "run." + task.run.status.toLowerCase(Locale.ROOT));
      active.remove(task.run.id, task);
      runConfigurations.remove(task.run.id);
      reportSavedAt.remove(task.run.id);
      shutdownRuns.remove(task.run.id);
    }
  }

  private void tick() {
    if (closing) return;
    for (TaskContext task : active.values()) {
      try {
        if (!task.run.completedAt.isBlank()) {
          persistCompleted(task);
        } else if (System.nanoTime() - reportSavedAt.getOrDefault(task.run.id, 0L)
            >= TimeUnit.SECONDS.toNanos(10)) {
          persistReport(task, "run.progress_saved");
          reportSavedAt.put(task.run.id, System.nanoTime());
        }
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(TaskManager.class)
            .warn("Retry saving run {} failed", task.run.id, e);
      }
    }
    try {
      for (Job job : config.jobs()) {
        try { schedule(job); }
        catch (Exception e) {
          // A bad/offline job must not prevent unrelated backup jobs from running.
          org.slf4j.LoggerFactory.getLogger(TaskManager.class)
              .warn("Scheduler check for job {} failed", job.id, e);
        }
      }
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(TaskManager.class).warn("Load scheduled jobs failed", e);
    }
  }

  private void schedule(Job j) throws Exception {
    if (closing || !j.enabled || active.values().stream().anyMatch(t -> t.run.jobId.equals(j.id))) return;
    Run latest = latestBackups.get(j.id);
    Instant last = latest == null ? Instant.EPOCH : Instant.parse(latest.startedAt);
    long delay = Math.max(1, j.intervalSeconds);
    if (latest != null && latest.status.equals("PAUSED")) return;
    if (latest != null && !latest.status.equals("COMPLETED")) {
      delay = Math.max(delay, 30);
      if (!latest.completedAt.isBlank()) last = Instant.parse(latest.completedAt);
    }
    if (!Instant.now().isAfter(last.plusSeconds(delay))) return;
    boolean pending = !inbox.pending(j.id).isEmpty();
    if (j.sourceMode.equals("AGENT")
        && (com.oadig.syncsrv.modules.discovery.AgentSource.state(store, j.id) == null || !pending)) return;
    if (j.discoveryMode.equals("EVENTS")) {
      SyncEngine.Checkpoint checkpoint = sync.checkpoint(j.id);
      boolean calibration = checkpoint == null || Instant.now()
          .isAfter(Instant.parse(checkpoint.lastFullScan()).plusSeconds(j.reconcileSeconds));
      if (calibration || pending) {
        Run run = new Run();
        run.jobId = j.id;
        run.trigger = checkpoint == null ? "BASELINE" : calibration ? "CALIBRATION" : "EVENTS";
        enqueue(run, j, "scheduler");
      }
    } else submit(j.id, "scheduler");
  }

  public RunReports.Report report(String id) throws Exception {
    TaskContext task = active.get(id);
    if (task != null) return report(task, "LIVE");
    Run run = ConfigurationService.require(store.get("run", id, Run.class), "执行记录不存在");
    RunReports.Report saved = store.get("run-report", id, RunReports.Report.class);
    if (saved == null) return RunReports.create(run, null, null, "LEGACY");
    return saved; // Preserve the exact report whose hash was appended to the audit chain.
  }

  private RunReports.Report report(TaskContext task, String coverage) {
    synchronized (task.run) {
      Run copy = Json.read(Json.write(task.run), Run.class);
      copy.progress = task.progress.snapshot(task.run);
      return RunReports.create(
          copy, runConfigurations.get(copy.id), task.metrics.snapshot(), coverage);
    }
  }

  public record Files(Run run, String coverage, List<RunProgress.FileRow> entries, int total, int page, int pageSize) {}

  public Files files(String id, int page) throws Exception {
    if (page < 1 || page > 1_000_000) throw new IllegalArgumentException("文件列表页码不正确");
    TaskContext task = active.get(id);
    if (task != null) {
      synchronized(task.run) {
        Run copy=Json.read(Json.write(task.run),Run.class);
        copy.progress=task.progress.snapshot(task.run);
        return new Files(copy,"LIVE",task.progress.page(page-1,task.run).entries(),copy.progress.entries(),page,RunProgress.PAGE_SIZE);
      }
    }
    Run run=ConfigurationService.require(store.get("run",id,Run.class),"执行记录不存在");
    if(run.progress==null) return new Files(run,"LEGACY",List.of(),0,page,RunProgress.PAGE_SIZE);
    RunProgress.Page saved=store.get("run-file-page",RunProgress.pageId(id,page-1),RunProgress.Page.class);
    var entries=saved==null ? List.<RunProgress.FileRow>of() : saved.entries();
    return new Files(run,"SAVED",entries,run.progress.entries(),page,RunProgress.PAGE_SIZE);
  }

  private void persistReport(TaskContext task, String action) throws Exception {
    synchronized (task) {
    if(action.equals("run.progress_saved") && !task.run.completedAt.isBlank()) return;
    RunReports.Report report;
    List<RunProgress.Pending> pages;
    synchronized (task.run) {
      report=report(task, task.run.completedAt.isBlank() ? "LIVE" : "MEASURED");
      pages=task.progress.pending(task.run);
    }
    List<Store.Change> changes=new ArrayList<>();
    changes.add(put("run",task.run.id,report.run()));
    changes.add(put("run-report",task.run.id,report));
    for(var page:pages) changes.add(put("run-file-page",RunProgress.pageId(task.run.id,page.number()),page.page()));
    store.commit(task.run.actor, action, task.run.id,
        Map.of("run",report.run(),"reportHash",Ids.hash(Json.bytes(report))),changes.toArray(Store.Change[]::new));
    task.progress.saved(pages);
    }
  }

  public synchronized com.oadig.syncsrv.modules.sync.StagingCleanup.Result cleanup(
      String jobId, String actor) throws Exception {
    if (busy()) throw new IllegalArgumentException("请先暂停执行后清理暂存文件");
    return new com.oadig.syncsrv.modules.sync.StagingCleanup(store, config)
        .clean(config.job(jobId), actor, jobId);
  }

  public static String safeMessage(Exception e) {
    return com.oadig.syncsrv.shared.web.SafeMessages.message(e);
  }

  public boolean busy() {
    return !active.isEmpty();
  }

  @Override
  public void close() throws Exception {
    synchronized (this) {
      closing = true;
      scheduler.shutdownNow();
      // Cancel the entire queue before interrupting a worker that could start its next item.
      for (TaskContext task : active.values()) {
        shutdownRuns.add(task.run.id); task.cancelled.set(true);
      }
      for (TaskContext task : active.values()) {
        if (List.of("QUEUED", "RUNNING", "PAUSING").contains(task.run.status)) {
          try { pause(task.run.id, "system", true); }
          catch (Exception failure) {
            shutdownRuns.add(task.run.id);
            task.cancelled.set(true);
            if (task.thread != null) task.thread.interrupt();
            org.slf4j.LoggerFactory.getLogger(TaskManager.class).warn("Shutdown pause failed", failure);
          }
        }
      }
      worker.shutdown();
    }
    boolean interrupted = Thread.interrupted();
    try {
      // Do not discard queued runnables: each one must persist its cancellation.
      while (!worker.isTerminated() || !scheduler.isTerminated()) {
        try {
          worker.awaitTermination(1, TimeUnit.SECONDS);
          scheduler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          interrupted = true;
          for (TaskContext task : active.values())
            if (task.thread != null) task.thread.interrupt();
        }
      }
      for (TaskContext task : active.values()) persistCompleted(task);
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
