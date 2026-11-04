package com.oadig.syncsrv.modules.discovery;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.transfer.TaskContext;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Durable control-plane commands. Once claimed, a command can never fall back to a second writer. */
public final class DeviceExecution {
  public static final String KIND = "device-command";
  public static class Command {
    public String id = "", agentId = "", runId = "", kind = "", status = "QUEUED",
        payload = "", resource = "", createdAt = "", completedAt = "", error = "";
    public long deadline;
    public Report result;
  }
  public record BoundEndpoint(Endpoint endpoint, String marker, String markerHash) {}
  public record Work(String id, String kind, BoundEndpoint source, String from,
      BoundEndpoint target, String to, long size, String hash, double bandwidthMiB, String limitScope) {
    public Work(String id,String kind,BoundEndpoint source,String from,BoundEndpoint target,String to,long size,String hash,double bandwidthMiB) { this(id,kind,source,from,target,to,size,hash,bandwidthMiB,""); }
  }
  public record Report(String id, String status, String phase, long transferred,
      long verified, String hash, long size, String error) {}
  public record Poll(String publicKey, String requestId, List<String> activeIds) {
    public Poll(String publicKey, String requestId) { this(publicKey, requestId, List.of()); }
  }
  public record Binding(String fingerprint, String token) {}
  private static final class Live {
    final Map<String, Long> agents = new ConcurrentHashMap<>();
    final Map<String, Report> progress = new ConcurrentHashMap<>();
    final Map<String, String> claimed = new ConcurrentHashMap<>();
    final Map<String, Object> bindingLocks = new ConcurrentHashMap<>();
  }
  private static final Map<Store, Live> STATES = Collections.synchronizedMap(new WeakHashMap<>());
  private final Store store;
  private final Live live;
  public DeviceExecution(Store store) {
    this.store = store;
    synchronized (STATES) { live = STATES.computeIfAbsent(store, s -> new Live()); }
  }
  public boolean available(String id) throws Exception {
    if (id == null || id.isBlank()) return false;
    var d = store.get("agent", id, AgentService.Device.class);
    return d != null && d.status.equals("APPROVED")
        && live.agents.getOrDefault(id, 0L) > System.currentTimeMillis() - 45000;
  }
  private static boolean terminal(String status) {
    return List.of("COMPLETED", "FAILED", "CANCELLED", "NOT_STARTED").contains(status);
  }
  private void approved(String id) throws Exception {
    var d = store.get("agent", id, AgentService.Device.class);
    if (d == null || !d.status.equals("APPROVED")) throw new SecurityException("执行设备未授权");
  }
  public static Set<String> activeIds(List<String> values) {
    if (values == null) return Set.of();
    if (values.size() > 16 || values.stream().anyMatch(v -> v == null || !v.matches("[a-f0-9]{32}"))
        || new HashSet<>(values).size() != values.size())
      throw new IllegalArgumentException("正在执行的命令列表不正确");
    return Set.copyOf(values);
  }
  public Command poll(String id) throws Exception { return poll(id, Set.of()); }
  public Command poll(String id, Set<String> active) throws Exception {
    approved(id);
    live.agents.put(id, System.currentTimeMillis());
    synchronized (store) {
      for (Command c : store.list("device-pending", Command.class)) {
        if (!c.agentId.equals(id) || terminal(c.status) || active.contains(c.id)) continue;
        if (active.size() >= 16) return null;
        if (c.status.equals("QUEUED")) {
          if (c.deadline < System.currentTimeMillis() && !busy(id)) {
            c.status = "NOT_STARTED";
            save(c, "device.not_started");
            continue;
          }
          c.deadline = System.currentTimeMillis() + 15000;
          c.status = "RUNNING";
          save(c, "device.claimed");
        }
        live.claimed.put(c.id, id);
        return c;
      }
    }
    return null;
  }
  private boolean busy(String agent) {
    return live.agents.getOrDefault(agent, 0L) > System.currentTimeMillis() - 45000
        && live.claimed.containsValue(agent);
  }
  public Command await(String agent) throws Exception { return await(agent, Set.of()); }
  public Command await(String agent, Set<String> active) throws Exception {
    Command command = poll(agent, active);
    if (command != null) return command;
    synchronized (live) {
      // Check under the notification lock to close the enqueue/wait race.
      command = poll(agent, active);
      if (command == null) live.wait(20000);
    }
    return poll(agent, active);
  }
  private void assertSettled(String resource) throws Exception {
    synchronized (store) {
      for (Command old : store.list("device-pending", Command.class)) {
        if (!old.resource.equals(resource) || terminal(old.status)) continue;
        if (old.status.equals("QUEUED") && old.deadline < System.currentTimeMillis() && !busy(old.agentId)) {
          old.status = "NOT_STARTED"; save(old, "device.not_started");
        } else throw new IOException("此文件仍有执行端任务未确认；等待回执后重试，禁止并行中转");
      }
    }
  }
  public Work work(Command c) throws Exception { return Json.read(store.decrypt(c.payload), Work.class); }
  public boolean report(String agent, Report r) throws Exception {
    if (r == null || r.id() == null || r.hash() == null || r.transferred() < 0 || r.verified() < 0
        || r.size() < 0 || r.error() == null || r.error().length() > 1000
        || r.phase() == null || !List.of("COPYING", "HASHING", "VERIFYING").contains(r.phase())
        || !List.of("RUNNING", "COMPLETED", "FAILED", "CANCELLED").contains(r.status()))
      throw new IllegalArgumentException("执行回执不正确");
    synchronized (store) {
      Command c = store.get(KIND, r.id(), Command.class);
      if (c == null || !c.agentId.equals(agent)) throw new SecurityException("执行任务不属于此设备");
      if (terminal(c.status)) return false;
      if (c.status.equals("QUEUED")) throw new SecurityException("执行任务尚未领取");
      Work w = work(c);
      if (r.status().equals("COMPLETED") && (!r.hash().matches("[a-f0-9]{64}")
          || (w.size() >= 0 && r.size() != w.size())
          || (w.kind().equals("COPY") && !w.hash().equals(r.hash()))))
        throw new IllegalArgumentException("执行回执的完整校验结果不匹配");
      live.agents.put(agent, System.currentTimeMillis());
      live.progress.put(c.id, r);
      if (!r.status().equals("RUNNING")) {
        c.status = r.status(); c.result = r; c.error = r.error(); c.completedAt = Ids.now();
        save(c, "device.result");
        live.progress.remove(c.id);
        live.claimed.remove(c.id);
        return false;
      }
      live.claimed.put(c.id, agent);
      return c.status.equals("RUNNING") && c.deadline >= System.currentTimeMillis()
          && store.get("agent", agent, AgentService.Device.class).status.equals("APPROVED");
    }
  }
  private void save(Command c, String action) throws Exception {
    store.commit(c.agentId, action, c.runId.isEmpty() ? c.id : c.runId,
        Map.of("commandId", c.id, "agentId", c.agentId, "kind", c.kind, "status", c.status,
            "error", c.error, "result", c.result == null ? Map.of() : c.result), put(KIND, c.id, c),
        terminal(c.status) ? remove("device-pending", c.id) : put("device-pending", c.id, c));
  }
  /** The challenge is read locally by the Agent before any content access. No pathname guessing. */
  public BoundEndpoint bind(Endpoint e, String executor) throws Exception {
    Endpoint selected = Json.read(Json.write(e), Endpoint.class);
    if (!executor.equals(e.agentId)) {
      if (e.protocol.equals("LOCAL")) throw new IOException("其他设备无法直接读取中心本地磁盘");
      selected.agentId = ""; selected.agentRoot = "";
      return new BoundEndpoint(selected, "", "");
    }
    String fingerprint = Ids.textHash(ConfigurationService.location(e) + "\n" + e.agentId + "\n" + e.agentRoot);
    Binding binding;
    synchronized (store) {
      binding = store.get("device-binding", e.id, Binding.class);
      if (binding == null || !binding.fingerprint().equals(fingerprint)) {
        binding = new Binding(fingerprint, Ids.next() + Ids.next());
        store.commit("system", "device.binding_created", e.id, Map.of("agentId", executor),
            put("device-binding", e.id, binding));
      }
    }
    String marker = ".syncsrv/device-bindings/" + e.id;
    byte[] token = binding.token().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    synchronized (live.bindingLocks.computeIfAbsent(e.id, ignored -> new Object())) {
      try (Storage remote = StorageFactory.open(e)) {
        Storage.Item item = remote.stat(marker);
        boolean matches = false;
        if (item != null && !item.directory() && !item.symbolic() && item.size() == token.length)
          try (InputStream input = remote.read(marker, 0, token.length + 1L)) {
            matches = Arrays.equals(token, input.readNBytes(token.length + 1));
          }
        if (!matches) {
          remote.mkdirs(StoragePaths.parent(marker));
          remote.write(marker, 0, new ByteArrayInputStream(token), token.length);
        }
      }
    }
    selected.protocol = "LOCAL"; selected.root = e.agentRoot;
    selected.password = ""; selected.username = ""; selected.host = "";
    return new BoundEndpoint(selected, marker, Ids.hash(token));
  }
  public static class NotStarted extends IOException { public NotStarted(String m) { super(m); } }
  private Report execute(Work template, String agent, TaskContext task, String resource) throws Exception {
    task.check();
    if (template.bandwidthMiB() > 0)
      task.metrics.note("设备端同一执行批次的文件共享限速器，并行执行互不冲突的文件；全局额度由中心统一预约");
    if (!available(agent)) throw new NotStarted("执行设备离线或尚未安装支持存储执行的版本");
    Command c = new Command(); c.id = Ids.next(); c.agentId = agent; c.kind = template.kind();
    c.runId = task.run.id; c.resource = resource; c.createdAt = Ids.now();
    Work w = new Work(c.id, template.kind(), template.source(), template.from(), template.target(),
        template.to(), template.size(), template.hash(), template.bandwidthMiB(),task.run.id);
    c.payload = store.encrypt(Json.write(w)); c.deadline = System.currentTimeMillis() + 15000;
    synchronized (store) {
      for (Command old : store.list("device-pending", Command.class)) {
        if (!old.resource.equals(resource) || terminal(old.status)) continue;
        if (old.status.equals("QUEUED") && old.deadline < System.currentTimeMillis() && !busy(old.agentId)) {
          old.status = "NOT_STARTED"; save(old, "device.not_started");
        } else throw new IOException("此文件仍有执行端任务未确认；等待回执后重试，禁止并行中转");
      }
      save(c, "device.queued");
    }
    synchronized (live) { live.notifyAll(); }
    long transferred = 0, verified = 0, renewed = System.currentTimeMillis(), started = renewed;
    try {
      while (true) {
        task.check();
        Command current;
        synchronized (store) {
          current = store.get(KIND, c.id, Command.class);
          long now = System.currentTimeMillis();
          if (current.status.equals("QUEUED") && now > current.deadline) {
            if (busy(agent)) {
              current.deadline = now + 15000; save(current, "device.queue_wait");
            } else {
              current.status = "NOT_STARTED"; save(current, "device.not_started");
            }
          } else if (current.status.equals("RUNNING") && now - renewed >= 5000) {
            current.deadline = now + 15000; renewed = now; save(current, "device.lease_renewed");
          }
        }
        Report progress = current.result != null ? current.result : live.progress.get(c.id);
        if (progress != null) {
          long copyDelta = Math.max(0, progress.transferred() - transferred), verifyDelta = Math.max(0, progress.verified() - verified);
          transferred += copyDelta; verified += verifyDelta;
          synchronized (task.run) {
            task.run.bytes += copyDelta;
            task.verified(verifyDelta);
          }
          for (long n = copyDelta; n > 0;) { int chunk = (int)Math.min(n, Integer.MAX_VALUE); task.progress.transferred(chunk); n -= chunk; }
          task.metrics.deviceWork(copyDelta, verifyDelta, w.kind().equals("COPY") && w.source().endpoint().protocol.equals("LOCAL") && w.target().endpoint().protocol.equals("LOCAL"));
        }
        if (terminal(current.status)) {
          if (current.status.equals("NOT_STARTED")) throw new NotStarted("执行设备未领取任务");
          if (current.status.equals("FAILED") && current.error.startsWith("DIRECT_CONNECTION_UNAVAILABLE:"))
            throw new NotStarted("设备直连未建立且已确认停止，改由中心中转");
          if (!current.status.equals("COMPLETED")) throw new IOException("执行设备返回 " + current.status + ": " + current.error);
          task.metrics.checksum();
          return current.result;
        }
        // A lost connection is never interpreted as a failed copy. Keep the durable command for reconciliation.
        if (System.currentTimeMillis() - started > 45000 && !available(agent))
          throw new IOException("执行设备失联，结果待确认；已保留任务，禁止自动中转");
        Thread.sleep(200);
      }
    } finally {
      synchronized (store) {
        Command current = store.get(KIND, c.id, Command.class);
        if (!terminal(current.status)) {
          current.status = current.status.equals("QUEUED") ? "NOT_STARTED" : "CANCEL_REQUESTED";
          current.deadline = 0; save(current, "device.cancel_requested");
        }
      }
    }
  }
  private void route(TaskContext task, String mode, String agent, String note) throws Exception {
    synchronized (task.run) {
      task.run.executionMode = mode; task.run.executionAgentId = agent; task.run.executionNote = note;
    }
    task.metrics.note(note);
    store.commit(task.run.actor, "transfer.route", task.run.id,
        Map.of("mode", mode, "agentId", agent, "reason", note));
  }
  private boolean relayAllowed(Storage.Location... locations) {
    for (var l : locations) if (l != null && !l.endpoint().agentId.isBlank() && !l.endpoint().allowRelay) return false;
    return true;
  }
  public String hash(Storage.Location location, long size, TaskContext task) throws Exception {
    if (location == null || location.endpoint().agentId.isBlank()) return null;
    Endpoint e = location.endpoint();
    assertSettled(ConfigurationService.location(e) + "|" + location.path());
    if (!available(e.agentId)) {
      if (!e.allowRelay) throw new IOException("执行设备不可用，且此连接未允许中心中转");
      route(task, "CENTER_RELAY", "", "执行设备不可用，中心读取校验"); return null;
    }
    route(task, "AGENT_LOCAL", e.agentId, "文件在所属设备本地校验");
    try (var phase = task.verifying("HASHING", location.path(), size)) {
      return execute(new Work("", "HASH", bind(e, e.agentId), location.path(), null, "",
          size == Long.MAX_VALUE ? -1 : size, "", limit(task)), e.agentId, task,
          ConfigurationService.location(e) + "|" + location.path()).hash();
    } catch (NotStarted ex) {
      if (!e.allowRelay) throw ex;
      route(task, "CENTER_RELAY", "", ex.getMessage()); return null;
    }
  }
  public boolean copy(Storage.Location source, Storage.Location target, long size, String hash, TaskContext task) throws Exception {
    if (source == null || target == null) return false;
    Endpoint a = source.endpoint(), b = target.endpoint();
    assertSettled(ConfigurationService.location(b) + "|" + target.path());
    assertSettled(ConfigurationService.location(a) + "|" + source.path());
    String agent = available(b.agentId) ? b.agentId : available(a.agentId) ? a.agentId : "";
    if (agent.isEmpty() || (a.protocol.equals("LOCAL") && !agent.equals(a.agentId))
        || (b.protocol.equals("LOCAL") && !agent.equals(b.agentId))) {
      if (!relayAllowed(source, target)) throw new IOException("没有可用的直连执行设备，且未允许中心中转");
      if (!a.agentId.isBlank() || !b.agentId.isBlank()) route(task, "CENTER_RELAY", "", "执行设备不可用，使用存储端复制或中心中转");
      return false;
    }
    String mode = agent.equals(a.agentId) && agent.equals(b.agentId) ? "AGENT_LOCAL" : "AGENT_DIRECT";
    route(task, mode, agent, mode.equals("AGENT_LOCAL") ? "同设备复制与校验，文件内容不经过中心" : "执行设备直接连接另一存储，文件内容不经过中心");
    try {
      Report result = execute(new Work("", "COPY", bind(a, agent), source.path(), bind(b, agent), target.path(),
          size, hash, limit(task)), agent, task, ConfigurationService.location(b) + "|" + target.path());
      if (!result.hash().equals(hash) || result.size() != size) throw new IOException("设备复制完整校验失败");
      return true;
    } catch (NotStarted ex) {
      if (!relayAllowed(source, target)) throw ex;
      route(task, "CENTER_RELAY", "", ex.getMessage()); return false;
    }
  }
  private double limit(TaskContext task) {
    double job = task.job.mib(), global = task.global.mib();
    return job <= 0 ? global : global <= 0 ? job : Math.min(job, global);
  }
}
