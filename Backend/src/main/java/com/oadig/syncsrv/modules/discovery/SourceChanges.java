package com.oadig.syncsrv.modules.discovery;

import static com.oadig.syncsrv.platform.persistence.Store.Change.put;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.StoragePaths;
import com.oadig.syncsrv.shared.*;
import java.time.*;
import java.util.*;

/** Early visibility only: these records NEVER authorize deletion or advance the source manifest. */
public final class SourceChanges {
  public static class Entry {
    public String id, jobId, agentId, epoch, eventId, path, observedAt, receivedAt, updatedAt;
    public String status = "WAITING", phase = "DEBOUNCING", startedAt = "", completedAt = "";
    public String runId = "", error = "", currentPath = "";
    public long coalesced;
  }
  public record Start(boolean allowed, long waitMillis) {}
  public record View(List<Entry> entries, int total, Map<String, Long> counts) {}
  private final Store store;
  private final ConfigurationService config;
  private final Clock clock;
  private static final String KIND = "source-change";

  public SourceChanges(Store store, ConfigurationService config) {
    this(store, config, Clock.systemUTC());
  }
  public SourceChanges(Store store, ConfigurationService config, Clock clock) {
    this.store = store;
    this.config = config;
    this.clock = clock;
  }
  private String now() { return clock.instant().toString(); }
  private String key(String agent, ChangeInbox.Event e) {
    return Ids.textHash(agent + ":" + e.jobId() + ":" + e.path());
  }
  private boolean same(Entry row, ChangeInbox.Batch batch, ChangeInbox.Event event) {
    return row != null && row.epoch.equals(batch.epoch()) && row.eventId.equals(event.id());
  }
  private Map<String, Models.Job> validate(String agent, ChangeInbox.Batch batch) throws Exception {
    AgentService.Device device = store.get("agent", agent, AgentService.Device.class);
    if (device == null || !device.status.equals("APPROVED")) throw new SecurityException("源端未授权");
    if (batch == null || batch.epoch() == null || !batch.epoch().matches("[A-Za-z0-9_-]{1,64}")
        || batch.events() == null || batch.events().isEmpty() || batch.events().size() > 512)
      throw new IllegalArgumentException("变化通知批次不正确");
    Set<String> paths = new HashSet<>();
    Map<String, Models.Job> jobs = new HashMap<>();
    for (var event : batch.events()) {
      if (event == null || event.id() == null || !event.id().matches("[0-9]{1,20}")
          || event.jobId() == null || event.path() == null || event.path().length() > 4096
          || event.coalesced() < 0 || event.kind() == null || !List.of("CHANGE", "RESTART", "RESCAN", "OVERFLOW").contains(event.kind()))
        throw new IllegalArgumentException("变化通知字段不正确");
      Instant.parse(event.observedAt());
      String path = StoragePaths.relative(event.path());
      if (!path.equals(event.path()) || path.equalsIgnoreCase(".syncsrv") || path.toLowerCase(Locale.ROOT).startsWith(".syncsrv/")
          || (!event.kind().equals("CHANGE") && !path.isEmpty()) || !paths.add(key(agent, event)))
        throw new IllegalArgumentException("变化通知路径不正确");
      var job = jobs.get(event.jobId());
      if (job == null) { job = config.job(event.jobId()); jobs.put(event.jobId(), job); }
      if (!job.agentId.equals(agent) || !job.sourceMode.equals("AGENT") || !job.discoveryMode.equals("EVENTS"))
        throw new SecurityException("源端不属于此直传任务");
    }
    return jobs;
  }
  private Map<String, Entry> rows(String agent, List<ChangeInbox.Event> events) throws Exception {
    return store.getMany(KIND, events.stream().map(e -> key(agent, e)).toList(), Entry.class);
  }
  public List<String> receive(String agent, ChangeInbox.Batch batch) throws Exception {
    synchronized (store) {
      validate(agent, batch);
      receiveValidated(agent, batch);
      return batch.events().stream().map(ChangeInbox.Event::id).toList();
    }
  }
  private void receiveValidated(String agent, ChangeInbox.Batch batch) throws Exception {
    Map<String, Entry> existing = rows(agent, batch.events());
    List<Entry> saved = new ArrayList<>();
    for (var event : batch.events()) {
      String id = key(agent, event);
      Entry old = existing.get(id);
      if (same(old, batch, event)) continue;
      // Notification and upload use independent connections; an older delivery cannot regress a path.
      if (old != null && old.epoch.equals(batch.epoch())
          && new java.math.BigInteger(old.eventId).compareTo(new java.math.BigInteger(event.id())) > 0) continue;
      Entry row = new Entry();
      row.id = id; row.jobId = event.jobId(); row.agentId = agent;
      row.epoch = batch.epoch(); row.eventId = event.id(); row.path = event.path();
      row.observedAt = event.observedAt(); row.receivedAt = now(); row.updatedAt = row.receivedAt;
      row.coalesced = event.coalesced();
      saved.add(row);
    }
    save(agent, "source.changes_notified", saved);
  }
  public Start start(String agent, ChangeInbox.Batch batch) throws Exception {
    synchronized (store) {
      var configured = validate(agent, batch);
      receiveValidated(agent, batch);
      var existing = rows(agent, batch.events());
      Set<String> jobs = new HashSet<>();
      for (var event : batch.events()) {
        if (!same(existing.get(key(agent, event)), batch, event)) return new Start(false, 1000);
        if (!configured.get(event.jobId()).enabled) return new Start(false, 5000);
        jobs.add(event.jobId());
      }
      Instant ready = clock.instant();
      for (Entry row : store.list(KIND, Entry.class)) {
        if (!jobs.contains(row.jobId) || !row.agentId.equals(agent) || !row.status.equals("WAITING")) continue;
        Instant due = Instant.parse(row.receivedAt).plusSeconds(configured.get(row.jobId).intervalSeconds);
        if (due.isAfter(ready)) ready = due;
      }
      long delay = Duration.between(clock.instant(), ready).toMillis();
      if (delay > 0) return new Start(false, delay);
      progress(agent, batch, "PREPARING", "");
      return new Start(true, 0);
    }
  }
  public void progress(String agent, ChangeInbox.Batch batch, String phase, String path) throws Exception {
    if(phase==null || path==null)throw new IllegalArgumentException("源端执行阶段与路径不能为空");
    if (phase.equals("SOURCE_BUSY")) {
      StoragePaths.relative(path);
      update(agent, batch, "WAITING", phase, path, "文件被其他程序占用，源端保留变化并自动重试；尚未完成备份。请等待写入结束或关闭占用程序。");
      return;
    }
    if (!List.of("PREPARING", "CONNECTING_B", "UPLOADING_B", "VERIFYING_B").contains(phase))
      throw new IllegalArgumentException("未知源端执行阶段");
    StoragePaths.relative(path);
    update(agent, batch, "RUNNING", phase, path, "");
  }
  public void ready(String agent, ChangeInbox.Batch batch) throws Exception {
    boolean announced = false;
    var existing = rows(agent, batch.events());
    for (var event : batch.events()) if (same(existing.get(key(agent, event)), batch, event)) announced = true;
    if (!announced) return;
    update(agent, batch, "READY", "WAITING_COMMIT", "", "");
  }
  public void failed(String agent, ChangeInbox.Batch batch) throws Exception {
    // Protocol error text can contain endpoint credentials. Keep the detailed error on A only.
    update(agent, batch, "FAILED", "SOURCE_RETRY", "", "源端读取或直连备份库失败，源端将自动重试；请检查源端日志与 源目录到备份库的网络及权限。");
  }
  /** Called only after the signed agent has abandoned its own upload session. */
  public void paused(String agent, String jobId, boolean revoked) throws Exception {
    synchronized(store) {
      var job=config.job(jobId);
      if(!job.agentId.equals(agent))throw new SecurityException("源端不属于此任务");
      var device=store.get("agent",agent,AgentService.Device.class);
      if(job.enabled && device!=null && device.status.equals("APPROVED"))return;
      List<Entry> saved=new ArrayList<>();
      String phase=revoked ? "AUTHORIZATION_REQUIRED" : "DEBOUNCING";
      for(Entry row:store.list(KIND,Entry.class)) {
        if(!row.agentId.equals(agent) || !row.jobId.equals(jobId)
            || row.phase.equals("CENTER_BACKUP") || !List.of("WAITING","RUNNING","FAILED").contains(row.status))continue;
        if(row.status.equals("WAITING") && row.phase.equals(phase) && row.error.isEmpty())continue;
        row.status="WAITING";row.phase=phase;row.error="";row.currentPath="";row.updatedAt=now();saved.add(row);
      }
      save(agent,"source.changes_paused",saved);
    }
  }
  private void update(String agent, ChangeInbox.Batch batch, String status, String phase, String path, String error) throws Exception {
    synchronized (store) {
      var configured = validate(agent, batch);
      // A file's progress only changes the events covering that file. Rewriting the whole
      // batch for every parallel worker creates quadratic database traffic.
      var selected = batch.events().stream().filter(e -> (!status.equals("RUNNING") && !phase.equals("SOURCE_BUSY")) || path.isEmpty()
          || e.path().isEmpty() || path.equals(e.path()) || path.startsWith(e.path() + "/")
          || e.path().startsWith(path + "/")).toList();
      var existing = rows(agent, selected);
      List<Entry> saved = new ArrayList<>();
      for (var event : selected) {
        Entry row = existing.get(key(agent, event));
        if (!same(row, batch, event) || row.status.equals("COMPLETED") || row.phase.equals("CENTER_BACKUP")) continue;
        if (row.status.equals(status) && row.phase.equals(phase) && row.currentPath.equals(path)
            && row.error.equals(error)) continue;
        boolean paused = status.equals("FAILED") && !configured.get(row.jobId).enabled;
        row.status = paused ? "WAITING" : status;
        row.phase = paused ? "DEBOUNCING" : phase;
        row.error = paused ? "" : error; row.currentPath = path;
        row.updatedAt = now();
        if (status.equals("RUNNING") && row.startedAt.isEmpty()) row.startedAt = now();
        saved.add(row);
      }
      save(agent, "source.changes_" + status.toLowerCase(Locale.ROOT), saved);
    }
  }
  public List<Entry> claim(ChangeInbox.Claim claim, String runId) throws Exception {
    synchronized (store) {
      List<Entry> saved = new ArrayList<>();
      for (Entry row : store.list(KIND, Entry.class)) {
        if (!row.jobId.equals(claim.jobId()) || !SyncEngine.within(row.path, claim.paths())) continue;
        if (!row.status.equals("READY") && !(List.of("FAILED", "RUNNING").contains(row.status) && row.phase.equals("CENTER_BACKUP"))) continue;
        row.status = "RUNNING"; row.phase = "CENTER_BACKUP"; row.runId = runId; row.updatedAt = now(); row.error = "";
        saved.add(row);
      }
      save("worker", "source.changes_committing", saved);
      return saved;
    }
  }
  public void finish(List<Entry> claim, String error) throws Exception {
    synchronized (store) {
      List<Entry> saved = new ArrayList<>();
      for (Entry previous : claim) {
        Entry row = store.get(KIND, previous.id, Entry.class);
        if (row == null || !row.epoch.equals(previous.epoch) || !row.eventId.equals(previous.eventId)
            || !row.runId.equals(previous.runId)) continue;
        row.status = error.isEmpty() ? "COMPLETED" : "FAILED";
        row.phase = error.isEmpty() ? "COMPLETE" : "CENTER_BACKUP";
        row.error = error.isEmpty() ? "" : "服务端备份未完成，请查看关联执行记录。";
        row.updatedAt = now();
        if (error.isEmpty()) row.completedAt = now();
        saved.add(row);
      }
      save("worker", error.isEmpty() ? "source.changes_completed" : "source.changes_commit_failed", saved);
    }
  }
  public View view() throws Exception { return view(null); }
  public View view(String jobId) throws Exception {
    var entries = store.list(KIND, Entry.class).stream()
        .filter(e -> jobId == null || e.jobId.equals(jobId))
        .sorted(Comparator.comparing((Entry e) -> e.updatedAt).reversed()).toList();
    Map<String, Long> counts = new TreeMap<>();
    for (Entry row : entries) counts.merge(row.status, 1L, Long::sum);
    return new View(entries.stream().limit(500).toList(), entries.size(), counts);
  }
  private void save(String actor, String action, List<Entry> entries) throws Exception {
    if (entries.isEmpty()) return;
    store.commit(actor, action, entries.get(0).jobId, Map.of("entries", entries),
        entries.stream().map(row -> put(KIND, row.id, row)).toArray(Store.Change[]::new));
  }
}
