package com.oadig.syncsrv.modules.archive;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.modules.archive.ArchiveModels.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.modules.sync.FileBatch;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.Storage;
import com.oadig.syncsrv.shared.*;
import java.io.*;
import java.util.*;

/** Per-file delete intents survive a crash between storage deletion and the audit receipt. */
final class ArchiveFiles {
  record Outcome(String planId, String itemId, String state, String runId, String time,
      long deletedBytes, String note) {}
  private final Store store;
  private final ConfigurationService config;
  private final FileBatch.Open open;

  ArchiveFiles(Store store, ConfigurationService config, FileBatch.Open open) {
    this.store = store; this.config = config; this.open = open;
  }
  static String key(String planId, String itemId) { return Ids.textHash(planId + ":" + itemId); }
  static boolean terminal(Outcome outcome) { return outcome != null && Set.of("REMOVED", "ABSENT_AFTER_INTENT", "PROTECTED").contains(outcome.state()); }

  void clean(Plan plan, Set<String> protectedPaths, Set<String> mismatched, TaskContext task) throws Exception {
    task.run.total = plan.items.size();
    for (Item item : plan.items) task.progress.plan(item.location(), "ARCHIVE_CLEANUP", item.size());
    task.progress.planned();
    int limit = Math.max(1, Math.min(4, config.job(plan.jobId).archiveThreads));
    task.progress.concurrency(limit);
    var endpoint = config.endpoint(config.job(plan.jobId).archiveId);
    ParallelWork.run(plan.items, limit, task, () -> new ParallelWork.Worker<Item>() {
      private Storage storage;
      public void apply(Item item) throws Exception {
        task.progress.begin(item.location(), "ARCHIVE_CLEANUP", item.size());
        try (var measured = task.metrics.operation(item.location(), "ARCHIVE_CLEANUP")) {
          try {
            task.check();
            Outcome previous = store.get("archive-item", key(plan.id, item.id()), Outcome.class);
            if (terminal(previous)) { finished(previous, task); return; }
            if (protectedPaths.contains(ArchivePaths.identity(item)) || mismatched.contains(item.id())) {
              Outcome skipped = result(plan, item, "PROTECTED", 0, "文件存在新的引用或登记已变化，已保留", task);
              save(plan, item, skipped); finished(skipped, task); return;
            }
            if (storage == null) storage = open.open(endpoint);
            ArchivePaths.validatePath(plan.jobId, item);
            boolean parents = parents(storage, item.location());
            Storage.Item before = parents ? storage.stat(item.location()) : null;
            if (before == null) {
              if (previous == null) throw new IOException("待清理文件在删除意图之前已缺失: " + item.location());
              Outcome recovered = result(plan, item, "ABSENT_AFTER_INTENT", 0,
                  "继续整理时确认文件已不存在；不重复计算释放空间", task);
              save(plan, item, recovered); finished(recovered, task); return;
            }
            ordinary(before, item);
            task.stage("ARCHIVE_VERIFY", item.location());
            verify(storage, item, task);
            task.check();
            Outcome intent = result(plan, item, "INTENT", 0, "已校验内容，准备删除", task);
            store.commit(task.run.actor, "archive.remove_intent", plan.id,
                Map.of("item", item, "outcome", intent), put("archive-item", key(plan.id, item.id()), intent));
            // Verify type and metadata again after the hash and before the destructive operation.
            if (!parents(storage, item.location())) throw new IOException("待清理文件父目录已变化");
            Storage.Item after = storage.stat(item.location());
            ordinary(after, item);
            if (after.modified() != before.modified()) throw new IOException("待清理文件在校验时发生变化");
            task.check(); task.stage("ARCHIVE_REMOVE", item.location());
            storage.delete(item.location(), false);
            if (storage.stat(item.location()) != null) throw new IOException("删除后文件仍存在，尚未确认清理成功");
            Outcome removed = result(plan, item, "REMOVED", item.size(), "文件删除已确认", task);
            save(plan, item, removed); task.metrics.cleaned(1, item.size()); finished(removed, task);
          } catch (Exception error) {
            measured.failed(error, task.cancelled.get());
            task.progress.finishFile(task.cancelled.get() ? "PAUSED" : "FAILED", TaskManager.safeMessage(error));
            throw error;
          }
        }
      }
      public void close() throws Exception { if (storage != null) storage.close(); }
    });
  }

  private static boolean parents(Storage storage, String path) throws Exception {
    Storage.Item root = storage.stat("");
    if (root == null || !root.directory() || root.symbolic()) throw new IOException("归档根目录不可用或不安全");
    String current = "";
    String[] parts = path.split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      current = current.isEmpty() ? parts[i] : current + "/" + parts[i];
      Storage.Item parent = storage.stat(current);
      if (parent == null) return false;
      if (!parent.directory() || parent.symbolic()) throw new IOException("归档父目录不安全: " + current);
    }
    return true;
  }

  private static void ordinary(Storage.Item actual, Item expected) throws IOException {
    if (actual == null || actual.directory() || actual.symbolic() || actual.size() != expected.size())
      throw new IOException("待清理文件类型或大小已变化: " + expected.location());
  }

  private static void verify(Storage storage, Item item, TaskContext task) throws Exception {
    var hash = Ids.digest();
    long count = 0;
    try (var progress = task.verifying("ARCHIVE_VERIFY", item.location(), item.size());
        InputStream input = task.paced(storage.read(item.location(), 0, item.size()), false)) {
      byte[] buffer = new byte[256 * 1024]; int n;
      while ((n = input.read(buffer)) != -1) { count += n; hash.update(buffer, 0, n); }
    }
    task.metrics.checksum();
    if (count != item.size() || !HexFormat.of().formatHex(hash.digest()).equals(item.hash()))
      throw new IOException("待清理文件内容与登记不一致，已保留: " + item.location());
  }

  private Outcome result(Plan plan, Item item, String state, long bytes, String note, TaskContext task) {
    return new Outcome(plan.id, item.id(), state, task.run.id, Ids.now(), bytes, note);
  }

  private void save(Plan original, Item item, Outcome result) throws Exception {
    synchronized (store) {
      if (terminal(store.get("archive-item", key(original.id, item.id()), Outcome.class))) return;
      List<Store.Change> changes = new ArrayList<>();
      if (!result.state().equals("PROTECTED"))
        for (String versionId : item.versionIds()) changes.add(remove("version", versionId));
      changes.add(put("archive-item", key(original.id, item.id()), result));
      store.commit(result.runId().isBlank() ? original.actor : actor(result.runId(), original.actor),
          "archive.file_" + result.state().toLowerCase(Locale.ROOT), original.id,
          Map.of("item", item, "outcome", result), changes.toArray(Store.Change[]::new));
    }
  }

  private String actor(String runId, String fallback) throws Exception {
    var run = store.get("run", runId, com.oadig.syncsrv.shared.Models.Run.class);
    return run == null ? fallback : run.actor;
  }

  private void finished(Outcome outcome, TaskContext task) {
    if (outcome.state().equals("PROTECTED")) {
      synchronized (task.run) { task.run.done++; task.progress.finishFile("SKIPPED", outcome.note()); }
    } else task.done();
  }
}
