package com.oadig.syncsrv.modules.archive;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.modules.archive.ArchiveModels.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.modules.sync.FileBatch;
import com.oadig.syncsrv.modules.transfer.TaskContext;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.StorageFactory;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.time.*;
import java.util.*;

/** Preview is read-only on storage. Only the common execution queue may call execute. */
public final class ArchiveService {
  public record Retirement(String planId, String runId, String time, String definitionHash) {}
  public record RetiredSnapshot(SnapshotSummary snapshot, String planId, String retiredAt) {}
  private final Store store;
  private final ConfigurationService config;
  private final ArchivePlanner planner;
  private final FileBatch.Open open;

  public ArchiveService(Store store, ConfigurationService config) {
    this(store, config, StorageFactory::open);
  }

  public ArchiveService(Store store, ConfigurationService config, FileBatch.Open open) {
    this.store = store; this.config = config; this.open = open;
    planner = new ArchivePlanner(store, config);
  }

  public Plan preview(String jobId, Request request, String actor) throws Exception {
    {
      for (Plan previous : store.list("archive-plan", Plan.class))
        if (jobId.equals(previous.jobId) && !"COMPLETED".equals(previous.state)
            && store.get("archive-retirement", previous.id, Retirement.class) != null)
          throw new IllegalArgumentException("此任务有尚未完成的归档整理，请先继续原清理计划");
      Plan plan = planner.plan(jobId, request);
      plan.actor = actor;
      store.commit(actor, "archive.previewed", plan.id, summary(plan), put("archive-plan", plan.id, plan));
      return plan;
    }
  }

  public Plan get(String jobId, String id) throws Exception {
    Plan plan = ConfigurationService.require(store.get("archive-plan", id, Plan.class), "整理计划不存在");
    if (!jobId.equals(plan.jobId)) throw new IllegalArgumentException("整理计划与任务不匹配");
    progress(plan);
    return plan;
  }

  public List<Plan> plans(String jobId) throws Exception {
    config.job(jobId);
    List<Plan> plans = store.list("archive-plan", Plan.class).stream()
        .filter(p -> p.jobId.equals(jobId))
        .sorted(Comparator.comparing((Plan p) -> p.createdAt).reversed()).limit(20).toList();
    Map<String, Run> runs = latestRuns();
    for (Plan plan : plans) {
      attachRun(plan, runs.get(plan.id)); progress(plan);
      // Polling history must not repeatedly send twenty complete deletion manifests.
      plan.items = List.of(); plan.expired = List.of(); plan.retained = List.of();
      plan.detailsOmitted = true;
    }
    return plans;
  }

  public Plan view(String jobId, String id) throws Exception {
    Plan plan = get(jobId, id);
    attachRun(plan, latestRuns().get(id));
    return plan;
  }

  private void progress(Plan plan) throws Exception {
    if (plan.state.equals("COMPLETED")) return;
    var keys = plan.items.stream().map(i -> ArchiveFiles.key(plan.id, i.id())).toList();
    var results = store.getMany("archive-item", keys, ArchiveFiles.Outcome.class).values();
    plan.completedFiles = 0; plan.skippedFiles = 0; plan.missingFiles = 0; plan.deletedBytes = 0;
    for (var result : results) {
      if (!ArchiveFiles.terminal(result)) continue;
      if (result.state().equals("PROTECTED")) plan.skippedFiles++;
      else { plan.completedFiles++; plan.deletedBytes = Math.addExact(plan.deletedBytes, result.deletedBytes()); }
      if (result.state().equals("ABSENT_AFTER_INTENT")) plan.missingFiles++;
    }
  }

  private Map<String, Run> latestRuns() throws Exception {
    Map<String, Run> result = new HashMap<>();
    for (Run run : store.list("run", Run.class)) if ("ARCHIVE_CLEANUP".equals(run.kind))
      result.merge(run.target, run, (a, b) -> a.startedAt.compareTo(b.startedAt) > 0 ? a : b);
    return result;
  }

  private void attachRun(Plan plan, Run run) {
    if (run == null) return;
    plan.runId = run.id;
    plan.runStatus = run.status;
    if ("COMPLETED".equals(plan.state)) return;
    if (List.of("FAILED", "PAUSED", "INTERRUPTED").contains(run.status)) {
      plan.state = "FAILED".equals(run.status) ? "FAILED" : "PAUSED";
      plan.error = run.error;
    }
  }

  public void confirm(String jobId, String id, boolean confirmed, String actor) throws Exception {
    if (!confirmed) throw new IllegalArgumentException("请先预览并明确确认清理范围");
    synchronized (store) {
      Plan plan = get(jobId, id);
      if ("COMPLETED".equals(plan.state)) throw new IllegalArgumentException("该整理计划已完成");
      if (store.get("archive-retirement", id, Retirement.class) == null) fresh(plan);
      store.commit(actor, "archive.confirmed", id, summary(plan));
    }
  }

  private void fresh(Plan plan) throws Exception {
    if (Instant.parse(plan.createdAt).plus(Duration.ofHours(1)).isBefore(Instant.now()))
      throw new IllegalArgumentException("整理预览已超过一小时，请重新预览");
    Plan current = planner.plan(plan.jobId, new Request(plan.beforeMonth, plan.keepSnapshotIds));
    if (!plan.fingerprint.equals(current.fingerprint)
        || !definition(plan).equals(definition(current)))
      throw new IllegalArgumentException("备份或引用已变化，请重新预览清理范围");
  }

  public void execute(String jobId, String id, TaskContext task) throws Exception {
    try {
      task.check(); task.stage("ARCHIVE_REVIEW", "");
      Plan plan = retire(jobId, id, task);
      if ("COMPLETED".equals(plan.state)) return;
      Set<String> protectedPaths;
      Set<String> mismatched;
      synchronized (store) {
        protectedPaths = planner.protectedLocations(jobId);
        var outcomes = store.getMany("archive-item", plan.items.stream()
            .map(item -> ArchiveFiles.key(id, item.id())).toList(), ArchiveFiles.Outcome.class);
        var remaining = plan.items.stream().filter(item -> !ArchiveFiles.terminal(
            outcomes.get(ArchiveFiles.key(id, item.id())))).toList();
        mismatched = planner.mismatchedItems(remaining);
      }
      new ArchiveFiles(store, config, open).clean(plan, protectedPaths, mismatched, task);
      synchronized (store) {
        Plan done = get(jobId, id);
        if (done.completedFiles + done.skippedFiles != done.items.size())
          throw new IllegalStateException("整理结果未全部确认，可继续原计划");
        done.state = "COMPLETED"; done.completedAt = Ids.now(); done.error = "";
        store.commit(task.run.actor, "archive.completed", id, summary(done), put("archive-plan", id, done));
      }
    } catch (Exception error) {
      boolean interrupted = Thread.interrupted();
      try {
        synchronized (store) {
          Plan failed = get(jobId, id);
          if (!"COMPLETED".equals(failed.state)) {
            failed.state = task.cancelled.get() || interrupted ? "PAUSED" : "FAILED";
            failed.error = TaskManager.safeMessage(error); failed.runId = task.run.id;
            store.commit(task.run.actor, "archive." + failed.state.toLowerCase(Locale.ROOT), id,
                Map.of("error", failed.error, "runId", task.run.id), put("archive-plan", id, failed));
          }
        }
      } catch (Exception persist) { error.addSuppressed(persist); }
      finally { if (interrupted) Thread.currentThread().interrupt(); }
      throw error;
    }
  }

  private Plan retire(String jobId, String id, TaskContext task) throws Exception {
    synchronized (store) {
      Plan plan = get(jobId, id);
      if ("COMPLETED".equals(plan.state)) return plan;
      if (!plan.endpointFingerprint.equals(planner.endpointFingerprint(jobId)))
        throw new IllegalArgumentException("归档存储配置已变化，禁止继续旧清理计划");
      String archiveId = config.job(jobId).archiveId;
      for (Item item : plan.items) {
        ArchivePaths.validatePath(jobId, item);
        if (!item.endpointId().equals(archiveId))
          throw new IllegalArgumentException("候选文件不属于归档库");
      }
      Retirement previous = store.get("archive-retirement", id, Retirement.class);
      List<Store.Change> changes = new ArrayList<>();
      if (previous == null) {
        fresh(plan);
        task.check(); task.stage("ARCHIVE_RETIRE", "");
        String at = Ids.now();
        for (SnapshotSummary snapshot : plan.expired) {
          changes.add(put("retired-snapshot", snapshot.id(), new RetiredSnapshot(snapshot, id, at)));
          changes.add(remove("snapshot", snapshot.id()));
          changes.add(remove("snapshot-summary", snapshot.id()));
        }
        changes.add(put("archive-retirement", id, new Retirement(id, task.run.id, at, definition(plan))));
      } else if (!previous.definitionHash().equals(definition(plan))) {
        throw new IllegalArgumentException("整理范围与已确认记录不一致");
      }
      plan.state = "CLEANING"; plan.runId = task.run.id; plan.error = "";
      changes.add(put("archive-plan", id, plan));
      store.commit(task.run.actor, previous == null ? "archive.snapshots_retired" : "archive.resumed",
          id, summary(plan), changes.toArray(Store.Change[]::new));
      return plan;
    }
  }

  static String definition(Plan p) {
    return Ids.hash(Json.bytes(List.of(p.jobId, p.beforeMonth, p.cutoff, p.endpointFingerprint,
        p.fingerprint, p.expired, p.items)));
  }

  private static Map<String, Object> summary(Plan p) {
    return Map.of("jobId", p.jobId, "cutoff", p.cutoff, "expiredSnapshots", p.expired.size(),
        "retainedSnapshots", p.retained.size(), "files", p.items.size(), "estimatedBytes", p.estimatedBytes,
        "deletedBytes", p.deletedBytes, "completedFiles", p.completedFiles,
        "skippedFiles", p.skippedFiles, "definitionHash", definition(p));
  }
}
