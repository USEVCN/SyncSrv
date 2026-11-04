package com.oadig.syncsrv.modules.archive;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.DeviceExecution;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.CurrentIndex;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.StoragePaths;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** A read-only reference graph. In particular, preview never invokes index migration. */
final class ArchiveReferences {
  record RestoreRef(String runId, String status, String snapshotId, RestoreService.Plan plan) {}
  record Graph(Job job, Endpoint archive, List<Snapshot> snapshots, Map<String, Version> versions,
      Map<String, Node> current, SyncEngine.Checkpoint checkpoint, List<Operation> operations,
      List<RestoreRef> restores) {}

  private final Store store;
  private final ConfigurationService config;
  ArchiveReferences(Store store, ConfigurationService config) { this.store = store; this.config = config; }

  Graph load(String jobId) throws Exception {
    {
      Job job = config.job(jobId);
      Endpoint archive = config.endpoint(job.archiveId);
      blockPending(archive);
      List<Snapshot> snapshots = store.list("snapshot", Snapshot.class).stream()
          .filter(s -> jobId.equals(s.jobId())).sorted(Comparator.comparing(Snapshot::id)).toList();
      Map<String, Version> versions = new TreeMap<>();
      for (Version version : store.list("version", Version.class)) {
        if (version.id() == null || versions.put(version.id(), version) != null)
          throw new IOException("版本标识重复或丢失，停止整理");
      }
      Map<String, Node> current = current(jobId);
      var checkpoint = store.get("sync-checkpoint", jobId, SyncEngine.Checkpoint.class);
      List<Operation> operations = store.list("operation", Operation.class).stream()
          .filter(op -> jobId.equals(op.jobId)).sorted(Comparator.comparing(op -> op.id)).toList();
      List<RestoreRef> restores = restores(jobId);
      Graph graph = new Graph(job, archive, snapshots, versions, current, checkpoint, operations, restores);
      for (Snapshot snapshot : snapshots) validateSnapshot(graph, snapshot);
      for (Node node : current.values()) validateNode(graph, node);
      Set<String> ids = new HashSet<>();
      for (Snapshot snapshot : snapshots) if (!ids.add(snapshot.id()))
        throw new IOException("恢复点标识重复，停止整理");
      Map<String, Snapshot> byId = new HashMap<>();
      for (Snapshot snapshot : snapshots) byId.put(snapshot.id(), snapshot);
      if (checkpoint != null) {
        Snapshot point = byId.get(checkpoint.snapshotId());
        if (point == null || point.entries().size() != checkpoint.entries())
          throw new IOException("当前同步检查点与恢复清单不一致，停止整理");
      }
      for (RestoreRef ref : restores) {
        Snapshot point = byId.get(ref.snapshotId());
        if (point == null) throw new IOException("未完成恢复的恢复点不存在，停止整理");
        if (ref.plan() != null) {
          Snapshot planned = ref.plan().snapshot();
          if (planned == null || !point.id().equals(planned.id())
              || !point.manifestHash().equals(planned.manifestHash()))
            throw new IOException("未完成恢复计划与执行恢复点不一致，停止整理");
          validateRestore(graph, ref.plan());
        }
      }
      // Validate operation references even when no current snapshot mentions the archived version.
      protectOperations(graph, new HashSet<>(), new HashSet<>());
      return graph;
    }
  }

  private Map<String, Node> current(String jobId) throws Exception {
    var format = store.get("current-index", jobId, CurrentIndex.Format.class);
    Collection<Node> nodes;
    if (format == null) {
      Node[] legacy = store.get("current", jobId, Node[].class);
      nodes = legacy == null ? List.of() : Arrays.asList(legacy);
    } else {
      if (format.version() != 1 || !jobId.equals(format.jobId()))
        throw new IOException("当前索引格式不匹配，停止整理");
      nodes = store.list(CurrentIndex.kind(jobId), Node.class);
    }
    Map<String, Node> result = new TreeMap<>();
    for (Node node : nodes) if (node == null || result.put(node.path(), node) != null)
      throw new IOException("当前索引路径重复或丢失，停止整理");
    return result;
  }

  private List<RestoreRef> restores(String jobId) throws Exception {
    List<RestoreRef> result = new ArrayList<>();
    Map<String, Integer> accounted = new HashMap<>();
    for (Run run : store.restoreRuns()) {
      if (!"RESTORE".equals(run.kind)) continue;
      RestoreService.Plan plan = store.get("restore-plan", run.id, RestoreService.Plan.class);
      if (plan != null) accounted.merge(Ids.hash(Json.bytes(plan)), 1, Integer::sum);
      boolean relevant = jobId.equals(run.jobId) || (plan != null && jobId.equals(plan.snapshot().jobId()));
      if (relevant && !"COMPLETED".equals(run.status))
        result.add(new RestoreRef(run.id, run.status, run.snapshotId, plan));
    }
    // A plan with missing run metadata is not proof of completion; retain it conservatively.
    for (RestoreService.Plan plan : store.list("restore-plan", RestoreService.Plan.class)) {
      String hash = Ids.hash(Json.bytes(plan));
      int known = accounted.getOrDefault(hash, 0);
      if (known > 0) { accounted.put(hash, known - 1); continue; }
      if (plan.snapshot() == null) throw new IOException("恢复计划损坏，停止整理");
      if (jobId.equals(plan.snapshot().jobId()))
        result.add(new RestoreRef("unknown:" + hash, "UNKNOWN", plan.snapshot().id(), plan));
    }
    result.sort(Comparator.comparing(RestoreRef::runId));
    return List.copyOf(result);
  }

  private void blockPending(Endpoint archive) throws Exception {
    var execution = new DeviceExecution(store);
    var locations = new ArchiveLocations(config, List.of());
    for (var command : store.list("device-pending", DeviceExecution.Command.class)) {
      if (List.of("COMPLETED", "FAILED", "CANCELLED", "NOT_STARTED").contains(command.status)) continue;
      var work = execution.work(command);
      if (work == null) throw new IOException("设备执行命令无法识别，停止整理");
      for (var bound : Arrays.asList(work.source(), work.target())) {
        if (bound == null) continue;
        if (bound.endpoint() == null || archive.id.equals(bound.endpoint().id)
            || locations.overlaps(archive, bound.endpoint()))
          throw new IOException("归档库仍有设备执行任务未确认，请等待其结束后整理");
      }
    }
  }

  static void validateSnapshot(Graph g, Snapshot snapshot) throws Exception {
    if (snapshot == null || !g.job().id.equals(snapshot.jobId()) || snapshot.id() == null
        || !snapshot.id().matches("[a-f0-9]{32}") || snapshot.entries() == null
        || !Ids.hash(Json.bytes(snapshot.entries())).equals(snapshot.manifestHash()))
      throw new IOException("恢复清单校验失败，停止整理");
    try { Instant.parse(snapshot.completedAt()); } catch (RuntimeException e) {
      throw new IOException("恢复点完成时间无效，停止整理", e);
    }
    Set<String> paths = new HashSet<>();
    Map<String, Node> tree = new HashMap<>();
    long bytes = 0;
    for (Node node : snapshot.entries()) {
      validateNode(g, node);
      if (!paths.add(node.path().toLowerCase(Locale.ROOT)))
        throw new IOException("恢复清单路径重复，停止整理");
      tree.put(node.path(), node);
      if (!node.directory()) bytes = Math.addExact(bytes, node.size());
    }
    for (Node node : tree.values()) {
      String parent = StoragePaths.parent(node.path());
      if (!parent.isEmpty() && (tree.get(parent) == null || !tree.get(parent).directory()))
        throw new IOException("恢复清单缺少父目录或父路径为文件，停止整理");
    }
    if (bytes != snapshot.totalBytes() || snapshot.sequence() < 1)
      throw new IOException("恢复点统计与清单不一致，停止整理");
  }

  private static void validateNode(Graph g, Node node) throws Exception {
    if (node == null || node.path() == null || node.path().isBlank())
      throw new IOException("恢复清单文件路径丢失，停止整理");
    StoragePaths.relative(node.path());
    String root = node.path().split("/", 2)[0];
    if (root.equalsIgnoreCase(".syncsrv")) throw new IOException("恢复清单使用系统保留目录，停止整理");
    if (node.directory()) {
      if (node.size() != 0 || !"".equals(node.hash()) || !"".equals(node.versionId()))
        throw new IOException("恢复清单目录身份异常，停止整理");
      return;
    }
    Version v = g.versions().get(node.versionId());
    if (v == null || !g.job().id.equals(v.jobId()) || !node.path().equals(v.path())
        || node.size() != v.size() || !Objects.equals(node.hash(), v.hash()))
      throw new IOException("历史版本与恢复清单不一致，停止整理: " + node.path());
    validateVersion(g, v);
  }

  static void validateVersion(Graph g, Version v) throws Exception {
    if (v.id() == null || !v.id().matches("[a-f0-9]{32}") || v.size() < 0 || v.hash() == null
        || !v.hash().matches("[a-f0-9]{64}") || !g.job().id.equals(v.jobId()))
      throw new IOException("历史版本身份无效，停止整理");
    if (v.path() == null || v.path().isBlank() || v.location() == null || v.location().isBlank())
      throw new IOException("历史版本路径为空，停止整理");
    StoragePaths.relative(v.path());
    StoragePaths.relative(v.location());
    if (g.job().targetId.equals(v.endpointId())) {
      if (!"current".equals(v.reason()) || !v.path().equals(v.location()))
        throw new IOException("备份库版本位置不一致，停止整理");
    } else if (g.job().archiveId.equals(v.endpointId())) {
      if (!List.of("replaced", "deleted").contains(v.reason()))
        throw new IOException("归档版本原因无效，停止整理");
      if (!ArchivePaths.versionLocation(g.job().id, v.path(), v.id(), v.reason()).equals(v.location()))
        throw new IOException("归档版本路径与身份不一致，停止整理");
    } else throw new IOException("历史版本不属于任务的备份库或归档库，停止整理");
  }

  private static void validateRestore(Graph g, RestoreService.Plan plan) throws Exception {
    validateSnapshot(g, plan.snapshot());
    Map<String, Node> nodes = new HashMap<>();
    for (Node node : plan.snapshot().entries()) if (!node.directory()) nodes.put(node.path(), node);
    if (plan.items() == null || plan.items().size() != nodes.size())
      throw new IOException("恢复计划与恢复清单不一致，停止整理");
    for (RestoreItem item : plan.items()) {
      Node node = nodes.remove(item.path());
      if (node == null || !node.versionId().equals(item.versionId()) || node.size() != item.size()
          || !node.hash().equals(item.hash())
          || !List.of(g.job().targetId, g.job().archiveId).contains(item.endpointId()))
        throw new IOException("恢复计划版本身份不一致，停止整理");
      StoragePaths.relative(item.location());
    }
  }

  static void addSnapshot(Graph g, Snapshot s, Set<String> versions, Set<String> locations) {
    locations.add(ArchivePaths.identity(g.job().archiveId, g.job().id + "/snapshots/" + s.id() + ".json"));
    for (Node n : s.entries()) if (!n.directory()) addVersion(g, n.versionId(), versions, locations);
  }

  static void addVersion(Graph g, String id, Set<String> versions, Set<String> locations) {
    versions.add(id);
    Version v = g.versions().get(id);
    if (v != null) locations.add(ArchivePaths.identity(v.endpointId(), v.location()));
  }

  static void protectExtra(Graph g, Set<String> versions, Set<String> locations) throws Exception {
    for (Node node : g.current().values()) if (!node.directory()) addVersion(g, node.versionId(), versions, locations);
    protectOperations(g, versions, locations);
    for (RestoreRef ref : g.restores()) if (ref.plan() != null) {
      addSnapshot(g, ref.plan().snapshot(), versions, locations);
      for (RestoreItem item : ref.plan().items())
        locations.add(ArchivePaths.identity(item.endpointId(), item.location()));
    }
  }

  private static void protectOperations(Graph g, Set<String> versions, Set<String> locations) throws Exception {
    for (Operation op : g.operations()) {
      for (Version version : Arrays.asList(op.oldVersion, op.newVersion)) if (version != null) {
        validateVersion(g, version);
        addVersion(g, version.id(), versions, locations);
        locations.add(ArchivePaths.identity(version.endpointId(), version.location()));
      }
      if (op.archivePath != null && !op.archivePath.isEmpty()) {
        if (op.oldVersion == null) throw new IOException("未完成归档操作缺少历史版本身份");
        var item = new ArchiveModels.Item(ArchivePaths.itemId(g.job().archiveId, op.archivePath),
            "VERSION", g.job().archiveId, op.archivePath, op.oldVersion.size(), op.oldVersion.hash(),
            List.of(op.oldVersion.id()), "");
        ArchivePaths.validatePath(g.job().id, item);
        locations.add(ArchivePaths.identity(item));
      }
    }
  }
}
