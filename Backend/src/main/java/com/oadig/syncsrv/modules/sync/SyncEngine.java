package com.oadig.syncsrv.modules.sync;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.util.*;

public final class SyncEngine {
  private final Store store;
  private final ConfigurationService config;
  private final TransferService transfer;
  private final TreeScanner scanner;
  private final FileOperations operations;
  private final FileBatch.Open open;
  private final FileBatch files;

  public SyncEngine(Store store, ConfigurationService config, TransferService transfer) {
    this(store, config, transfer, endpoint -> StorageFactory.open(endpoint, store));
  }

  public SyncEngine(Store store, ConfigurationService config, TransferService transfer, FileBatch.Open open) {
    this.store = store;
    this.config = config;
    this.open = open;
    this.transfer = transfer;
    scanner = new TreeScanner(transfer);
    operations = new FileOperations(store, transfer);
    files = new FileBatch(config, operations, open);
  }

  public List<Snapshot> snapshots() throws Exception {
    return store.list("snapshot", Snapshot.class).stream()
        .sorted(Comparator.comparing(Snapshot::completedAt).reversed())
        .toList();
  }

  public List<SnapshotSummary> summaries() throws Exception {
    return store.list("snapshot-summary", SnapshotSummary.class).stream()
        .sorted(Comparator.comparing(SnapshotSummary::completedAt).reversed())
        .toList();
  }

  public Snapshot snapshot(String id) throws Exception {
    Snapshot s = store.get("snapshot", id, Snapshot.class);
    if (s == null && store.get("retired-snapshot", id,
        com.oadig.syncsrv.modules.archive.ArchiveService.RetiredSnapshot.class) != null)
      throw new IllegalArgumentException("该恢复节点已通过归档整理移除，请选择保留的恢复节点");
    ConfigurationService.require(s, "恢复节点不存在");
    if (!Ids.hash(Json.bytes(s.entries())).equals(s.manifestHash()))
      throw new IOException("恢复清单校验失败");
    return s;
  }

  public Map<String, Node> current(String jobId) throws Exception {
    return new CurrentIndex(store).load(jobId);
  }

  public record Checkpoint(String lastFullScan, String snapshotId, int entries) {}

  public Checkpoint checkpoint(String jobId) throws Exception {
    return store.get("sync-checkpoint", jobId, Checkpoint.class);
  }

  public void sync(Job job, TaskContext task) throws Exception {
    sync(job, task, null);
  }

  public void sync(Job job, TaskContext task, Collection<String> changedPaths) throws Exception {
    if (changedPaths != null && checkpoint(job.id) == null) changedPaths = null;
    List<String> affected = changedPaths == null ? null : new ArrayList<>(changedPaths);
    String started = Ids.now();
    task.stage("PREPARING", "");
    Map<String, Node> current = new java.util.concurrent.ConcurrentSkipListMap<>(current(job.id));
    task.progress.concurrency(Math.max(1, Math.min(16, job.archiveThreads)));
    try (Storage a =
            job.sourceMode.equals("AGENT")
                ? new com.oadig.syncsrv.modules.discovery.AgentStorage(store, config, job)
                : open.open(config.endpoint(job.sourceId));
        Storage b = open.open(config.endpoint(job.targetId));
        Storage c = open.open(config.endpoint(job.archiveId))) {
      for (Storage s : List.of(a, b, c)) {
        Storage.Item root = s.stat("");
        if (root == null || !root.directory() || root.symbolic())
          throw new IOException("源目录、备份库与归档库根目录不可访问");
      }
      // Resume committed intents before validating the B tree against the current manifest.
      List<Operation> recovery = store.list("operation", Operation.class).stream().filter(op -> op.jobId.equals(job.id)).toList();
      for (Operation op : recovery) task.progress.plan(op.path, op.kind, op.entry.directory() ? 0 : op.entry.size());
      for (Operation op : recovery)
        if (op.jobId.equals(job.id)) {
          task.run.discoveryMode = "RECOVERY";
          task.metrics.recovered();
          if (!operations.supersedeUnstaged(op, a, b, task)) {
            task.run.total++;
            operations.apply(op, job, a, b, c, current, task);
          } else {
            task.progress.begin(op.path, op.kind, op.entry.size());
            task.progress.finishFile("SKIPPED", "源文件已有更新，旧操作已跳过");
          }
          if (affected != null) affected.add(op.path);
        }
      if (affected != null) affected = scopes(a, affected, current);
      boolean full = affected == null || affected.contains("");
      task.run.discoveryMode = full ? "FULL" : "EVENTS";
      task.stage("CHECKING_TARGET", "");
      Map<String, Node> actualB =
          full
              ? scanner.scan(b, task, true, true, job.archiveThreads,
                  () -> open.open(config.endpoint(job.targetId)))
              : scanner.scanPaths(b, task, affected, true, true, job.archiveThreads,
                  () -> open.open(config.endpoint(job.targetId)));
      Map<String, Node> expectedB = new TreeMap<>();
      for (Node n : current.values())
        if (full || within(n.path(), affected)) expectedB.put(n.path(), n);
      if (!actualB.keySet().equals(expectedB.keySet()))
        throw new IOException("备份库包含未知或缺失路径；首次备份要求备份库为空，后续请勿手动修改备份库");
      for (Node old : expectedB.values())
        if (!TreeScanner.sameContent(old, actualB.get(old.path())))
          throw new IOException("备份库内容与已记录版本不一致: " + old.path());
      task.stage(full ? "SCANNING" : "CHECKING_CHANGES", "");
      Map<String, Node> observed =
          full
              ? scanner.scan(a, task, false, true, job.archiveThreads,
                  () -> open.open(config.endpoint(job.sourceId)))
              : scanner.scanPaths(a, task, affected, false, true, job.archiveThreads,
                  () -> open.open(config.endpoint(job.sourceId)));
      Map<String, Node> second =
          full
              ? scanner.scan(a, task, false, false)
              : scanner.scanPaths(a, task, affected, false, false);
      if (!observed.keySet().equals(second.keySet())) throw new IOException("扫描期间目录变化，请重试");
      for (Node n : observed.values()) {
        Node check = second.get(n.path());
        if (n.directory() != check.directory()
            || n.size() != check.size()
            || n.modified() != check.modified()) throw new IOException("扫描期间文件变化: " + n.path());
      }
      Map<String, Node> source = new TreeMap<>(current);
      if (full) source.clear();
      else
        for (String path : new ArrayList<>(source.keySet()))
          if (within(path, affected)) source.remove(path);
      source.putAll(observed);
      Set<String> folded = new HashSet<>();
      for (String path : source.keySet())
        if (!folded.add(path.toLowerCase(Locale.ROOT)))
          throw new IOException("存在大小写冲突的路径: " + path);
      List<Node> removals =
          current.values().stream()
              .filter(
                  n ->
                      !source.containsKey(n.path())
                          || source.get(n.path()).directory() != n.directory())
              .sorted(
                  Comparator.comparingInt((Node n) -> TreeScanner.depth(n.path()))
                      .reversed()
                      .thenComparing(Node::path))
              .toList();
      List<Node> directories =
          source.values().stream()
              .filter(Node::directory)
              .sorted(Comparator.comparingInt(n -> TreeScanner.depth(n.path())))
              .toList();
      List<Node> writes =
          source.values().stream()
              .filter(n -> !n.directory())
              .filter(
                  n ->
                      !current.containsKey(n.path())
                          || !TreeScanner.sameContent(n, current.get(n.path())))
              .toList();
      task.run.total =
          task.run.done
              + removals.size()
              + writes.size()
              + (int)
                  directories.stream()
                      .filter(
                          n -> !current.containsKey(n.path()) || !current.get(n.path()).directory())
                      .count();
      for (Node old : removals) task.progress.plan(old.path(), old.directory() ? "RMDIR" : "DELETE", old.size());
      for (Node dir : directories) if (!current.containsKey(dir.path()) || !current.get(dir.path()).directory()) task.progress.plan(dir.path(), "MKDIR", 0);
      for (Node n : writes) task.progress.plan(n.path(), "WRITE", n.size());
      task.progress.planned();
      store.commit(
          task.run.actor,
          "sync.plan",
          task.run.id,
          Map.of(
              "sourceEntries",
              source.size(),
              "removals",
              removals.size(),
              "writes",
              writes.size(),
              "centerFileConcurrency", job.archiveThreads));
      files.apply(removals.stream().filter(n -> !n.directory()).toList(), "DELETE",
          job, a, b, c, current, task);
      for (Node old : removals.stream().filter(Node::directory).toList())
        operations.apply(
            operations.plan(job, old.directory() ? "RMDIR" : "DELETE", old, old, task),
            job,
            a,
            b,
            c,
            current,
            task);
      for (Node dir : directories)
        if (!current.containsKey(dir.path()))
          operations.apply(
              operations.plan(job, "MKDIR", dir, null, task), job, a, b, c, current, task);
      files.apply(writes, "WRITE", job, a, b, c, current, task);
      // A deleted path that reappeared must be included in a later complete scan, never a partial
      // snapshot.
      if (!current.keySet().equals(source.keySet())) throw new IOException("源目录在提交期间变化，稍后重新扫描");
      for (Node n : source.values())
        if (!TreeScanner.sameContent(n, current.get(n.path())))
          throw new IOException("提交期间内容变化，请重试");
      task.stage("SNAPSHOT", "");
      List<Node> entries = List.copyOf(current.values());
      int seq =
          summaries().stream()
                  .filter(s -> s.jobId().equals(job.id))
                  .mapToInt(SnapshotSummary::sequence)
                  .max()
                  .orElse(0)
              + 1;
      Snapshot snapshot =
          new Snapshot(
              Ids.next(),
              job.id,
              seq,
              started,
              Ids.now(),
              entries,
              Ids.hash(Json.bytes(entries)),
              entries.stream().filter(n -> !n.directory()).mapToLong(Node::size).sum());
      byte[] manifest = Json.bytes(snapshot);
      String path = job.id + "/snapshots/" + snapshot.id() + ".json";
      c.mkdirs(StoragePaths.parent(path));
      task.write(c, path, 0, new ByteArrayInputStream(manifest), manifest.length);
      operations.requireMatch(c, path, manifest.length, Ids.hash(manifest), task);
      store.commit(
          task.run.actor,
          "snapshot.completed",
          task.run.id,
          Map.of(
              "snapshotId",
              snapshot.id(),
              "manifestHash",
              snapshot.manifestHash(),
              "recordHash",
              Ids.hash(manifest),
              "archivePath",
              path),
          put("snapshot", snapshot.id(), snapshot),
          put("snapshot-summary", snapshot.id(), SnapshotSummary.from(snapshot)),
          put(
              "sync-checkpoint",
              job.id,
              new Checkpoint(
                  full ? snapshot.completedAt() : checkpoint(job.id).lastFullScan(),
                  snapshot.id(),
                  entries.size())));
      task.run.snapshotId = snapshot.id();
      task.stage("CLEANUP", "");
      if (job.sourceMode.equals("AGENT"))
        new com.oadig.syncsrv.modules.discovery.AgentPushService(store, config).cleanup(job);
      try {
        var cleaned = new StagingCleanup(store, config).clean(job, task.run.actor, task.run.id);
        task.metrics.cleaned(cleaned.files(), cleaned.bytes());
      } catch (Exception cleanup) {
        task.metrics.note(
            "暂存清理未完成：" + com.oadig.syncsrv.modules.jobs.TaskManager.safeMessage(cleanup));
        store.commit(
            task.run.actor,
            "staging.cleanup_failed",
            task.run.id,
            Map.of("reason", cleanup.getClass().getSimpleName()));
      }
    }
  }

  public static boolean within(String path, Collection<String> scopes) {
    return scopes.stream().anyMatch(p -> p.isEmpty() || path.equals(p) || path.startsWith(p + "/"));
  }

  private List<String> scopes(Storage source, Collection<String> paths, Map<String, Node> current)
      throws Exception {
    Set<String> candidates = new TreeSet<>();
    for (String path : paths) {
      StoragePaths.relative(path);
      if (path.isEmpty()) return List.of("");
      String prefix = "";
      for (String part : path.split("/")) {
        prefix = prefix.isEmpty() ? part : prefix + "/" + part;
        Storage.Item item = source.stat(prefix);
        if (item != null && item.symbolic()) throw new IOException("源路径存在符号链接: " + prefix);
        if (item == null || !item.directory() || !current.containsKey(prefix)) break;
      }
      candidates.add(prefix);
    }
    List<String> result = new ArrayList<>();
    for (String path : candidates) if (!within(path, result)) result.add(path);
    return result;
  }
}
