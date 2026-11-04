package com.oadig.syncsrv.modules.archive;

import com.oadig.syncsrv.modules.archive.ArchiveModels.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Month-based, reference-aware retention. Creating a plan neither writes metadata nor touches C. */
public final class ArchivePlanner {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final Comparator<Snapshot> ORDER = Comparator
      .comparing((Snapshot s) -> Instant.parse(s.completedAt()))
      .thenComparingInt(Snapshot::sequence).thenComparing(Snapshot::id);
  private final Store store;
  private final ConfigurationService config;
  private final ArchiveReferences references;

  public ArchivePlanner(Store store, ConfigurationService config) {
    this.store = store; this.config = config; references = new ArchiveReferences(store, config);
  }

  public Plan plan(String jobId, Request request) throws Exception {
    YearMonth month = month(request == null ? null : request.beforeMonth());
    Instant cutoff = month.atDay(1).atStartOfDay(ZONE).toInstant();
    List<String> explicit = keeps(request.keepSnapshotIds());
    return store.readSnapshot(() -> {
      var g = references.load(jobId);
      Map<String, LinkedHashSet<String>> reasons = reasons(g, cutoff, explicit);
      List<Snapshot> expired = g.snapshots().stream().filter(s -> !reasons.containsKey(s.id()))
          .sorted(ORDER).toList();
      Set<String> protectedVersions = new HashSet<>(), protectedLocations = new HashSet<>();
      for (Snapshot s : g.snapshots()) if (reasons.containsKey(s.id()))
        ArchiveReferences.addSnapshot(g, s, protectedVersions, protectedLocations);
      ArchiveReferences.protectExtra(g, protectedVersions, protectedLocations);
      Set<String> expiredVersions = new TreeSet<>();
      for (Snapshot s : expired) for (Node n : s.entries()) if (!n.directory()) expiredVersions.add(n.versionId());
      expiredVersions.addAll(ArchiveDeferred.versions(store, g.job(), cutoff, g.versions(),
          endpointFingerprint(g.job(), g.archive())));
      ArchiveLocations allLocations = new ArchiveLocations(config, g.versions().values());
      List<Item> items = new ArrayList<>();
      Set<String> considered = new HashSet<>();
      for (String id : expiredVersions) {
        Version v = g.versions().get(id); // Every snapshot was checked before selecting candidates.
        String location = ArchivePaths.identity(v.endpointId(), v.location());
        if (!jobId.equals(v.jobId()) || !g.job().archiveId.equals(v.endpointId())
            || protectedVersions.contains(id) || protectedLocations.contains(location)
            || !considered.add(location)) continue;
        List<Version> aliases = allLocations.aliases(v.endpointId(), v.location());
        // An unreferenced/orphan alias is not evidence that the underlying file is disposable.
        if (aliases.stream().anyMatch(a -> !jobId.equals(a.jobId()) || !expiredVersions.contains(a.id())
            || protectedVersions.contains(a.id()))) continue;
        for (Version alias : aliases) if (alias.size() != v.size() || !alias.hash().equals(v.hash()))
          throw new IOException("归档物理文件对应多个不一致的版本身份，停止整理");
        List<String> ids = aliases.stream().map(Version::id).sorted().toList();
        Item item = new Item(ArchivePaths.itemId(v.endpointId(), v.location()), "VERSION", v.endpointId(),
            v.location(), v.size(), v.hash(), ids, "");
        validatePath(jobId, item); items.add(item);
      }
      for (Snapshot s : expired) {
        byte[] bytes = Json.bytes(s);
        String path = jobId + "/snapshots/" + s.id() + ".json";
        if (!allLocations.aliases(g.job().archiveId, path).isEmpty()) continue;
        Item item = new Item(ArchivePaths.itemId(g.job().archiveId, path), "MANIFEST", g.job().archiveId,
            path, bytes.length, Ids.hash(bytes), List.of(), s.id());
        validatePath(jobId, item); items.add(item);
      }
      items.sort(Comparator.comparing(Item::location).thenComparing(Item::kind).thenComparing(Item::id));
      Plan plan = new Plan();
      plan.id = Ids.next(); plan.jobId = jobId; plan.beforeMonth = month.toString();
      plan.cutoff = cutoff.toString(); plan.createdAt = Ids.now(); plan.keepSnapshotIds = explicit;
      plan.expired = expired.stream().map(SnapshotSummary::from).toList();
      plan.retained = g.snapshots().stream().filter(s -> reasons.containsKey(s.id())).sorted(ORDER.reversed())
          .map(s -> new Kept(SnapshotSummary.from(s), List.copyOf(reasons.get(s.id())))).toList();
      plan.items = List.copyOf(items);
      for (Item item : items) plan.estimatedBytes = Math.addExact(plan.estimatedBytes, item.size());
      plan.endpointFingerprint = endpointFingerprint(g.job(), g.archive());
      plan.fingerprint = fingerprint(g, plan);
      return plan;
    });
  }

  private Map<String, LinkedHashSet<String>> reasons(ArchiveReferences.Graph g, Instant cutoff,
      List<String> explicit) throws IOException {
    Map<String, LinkedHashSet<String>> reasons = new TreeMap<>();
    Map<String, Snapshot> byId = new HashMap<>();
    for (Snapshot s : g.snapshots()) {
      byId.put(s.id(), s);
      if (!Instant.parse(s.completedAt()).isBefore(cutoff)) keep(reasons, s.id(), "指定月份及之后的恢复点");
    }
    g.snapshots().stream().max(ORDER).ifPresent(s -> keep(reasons, s.id(), "最新恢复点"));
    g.snapshots().stream().max(Comparator.comparingInt(Snapshot::sequence).thenComparing(ORDER))
        .ifPresent(s -> keep(reasons, s.id(), "最近一次执行的恢复点"));
    g.snapshots().stream().filter(s -> Instant.parse(s.completedAt()).isBefore(cutoff)).max(ORDER)
        .ifPresent(s -> keep(reasons, s.id(), "截止时间之前的基准恢复点"));
    if (g.checkpoint() != null) keep(reasons, g.checkpoint().snapshotId(), "当前同步检查点");
    for (String id : explicit) {
      if (!byId.containsKey(id)) throw new IOException("指定保留的恢复点不存在或不属于此任务");
      keep(reasons, id, "手动保留");
    }
    for (var restore : g.restores()) keep(reasons, restore.snapshotId(), "未完成的恢复正在引用");
    return reasons;
  }

  private static void keep(Map<String, LinkedHashSet<String>> reasons, String id, String reason) {
    reasons.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(reason);
  }

  /** Recalculate live references after retirement, including before resuming an interrupted plan. */
  public Set<String> protectedLocations(String jobId) throws Exception {
    synchronized (store) {
      var g = references.load(jobId);
      Set<String> versions = new HashSet<>(), locations = new HashSet<>();
      for (Snapshot snapshot : g.snapshots()) ArchiveReferences.addSnapshot(g, snapshot, versions, locations);
      ArchiveReferences.protectExtra(g, versions, locations);
      return Set.copyOf(locations);
    }
  }

  /** One scan for a batch: a new physical alias or changed version invalidates that candidate. */
  public Set<String> mismatchedItems(List<Item> items) throws Exception {
    synchronized (store) {
      ArchiveLocations locations = new ArchiveLocations(config, store.list("version", Version.class));
      Map<String, Snapshot> snapshots = store.getMany("snapshot", items.stream()
          .filter(i -> "MANIFEST".equals(i.kind())).map(Item::snapshotId).toList(), Snapshot.class);
      Set<String> mismatch = new HashSet<>();
      for (Item item : items) {
        if ("VERSION".equals(item.kind())) {
          List<Version> matches = locations.aliases(item.endpointId(), item.location());
          Set<String> ids = new TreeSet<>();
          for (Version v : matches) ids.add(v.id());
          if (matches.size() != item.versionIds().size() || !ids.equals(new TreeSet<>(item.versionIds()))
              || matches.stream().anyMatch(v -> !ArchivePaths.registered(item, v)))
            mismatch.add(item.id());
        } else if ("MANIFEST".equals(item.kind())) {
          Snapshot snapshot = snapshots.get(item.snapshotId());
          if (!locations.aliases(item.endpointId(), item.location()).isEmpty()) mismatch.add(item.id());
          if (snapshot != null) {
            byte[] bytes = Json.bytes(snapshot);
            if (bytes.length != item.size() || !Ids.hash(bytes).equals(item.hash())) mismatch.add(item.id());
          }
        } else mismatch.add(item.id());
      }
      return Set.copyOf(mismatch);
    }
  }

  public boolean matchesRegistered(Item item) throws Exception { return !mismatchedItems(List.of(item)).contains(item.id()); }
  public String endpointFingerprint(String jobId) throws Exception {
    Job job = config.job(jobId);
    return endpointFingerprint(job, config.endpoint(job.archiveId));
  }
  private static String endpointFingerprint(Job job, Endpoint e) throws Exception {
    return Ids.hash(Json.bytes(List.of(job.id, job.archiveId, ConfigurationService.location(e),
        e.agentId, e.agentRoot, e.username, e.domain, e.hostKey, e.protocol, e.host, e.port, e.root)));
  }
  public static void validatePath(String jobId, Item item) throws IOException { ArchivePaths.validatePath(jobId, item); }
  public static String identity(Item item) { return ArchivePaths.identity(item); }

  private String fingerprint(ArchiveReferences.Graph g, Plan p) {
    Map<String, Object> graph = new TreeMap<>();
    graph.put("request", new Request(p.beforeMonth, p.keepSnapshotIds));
    graph.put("endpoint", p.endpointFingerprint); graph.put("job", g.job());
    // Full snapshot byte hashes cover both manifest content and the metadata used for expiry.
    graph.put("snapshots", g.snapshots().stream().map(s -> List.of(s.id(), Ids.hash(Json.bytes(s)))).toList());
    graph.put("versions", List.copyOf(g.versions().values()));
    graph.put("current", g.current()); graph.put("checkpoint", g.checkpoint());
    graph.put("operations", g.operations()); graph.put("restores", g.restores());
    graph.put("items", p.items); graph.put("expired", p.expired); graph.put("retained", p.retained);
    return Ids.hash(Json.bytes(graph));
  }

  private static YearMonth month(String value) {
    if (value == null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])"))
      throw new IllegalArgumentException("请输入有效年月，格式为 yyyy-MM");
    YearMonth result = YearMonth.parse(value);
    if (result.getYear() < 1 || result.isAfter(YearMonth.now(ZONE)))
      throw new IllegalArgumentException("整理月份不能晚于当前月份");
    return result;
  }
  private static List<String> keeps(List<String> ids) {
    if (ids == null) return List.of();
    if (ids.stream().anyMatch(id -> id == null || !id.matches("[a-f0-9]{32}")))
      throw new IllegalArgumentException("保留恢复点标识无效");
    return ids.stream().distinct().sorted().toList();
  }
}
