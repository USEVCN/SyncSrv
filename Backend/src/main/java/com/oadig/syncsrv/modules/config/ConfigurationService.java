package com.oadig.syncsrv.modules.config;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

public final class ConfigurationService {
  private final Store store;

  public ConfigurationService(Store store) {
    this.store = store;
  }

  public List<Endpoint> endpoints() throws Exception {
    return store.list("endpoint", Endpoint.class).stream().map(Endpoint::safe).toList();
  }

  public List<Job> jobs() throws Exception {
    return store.list("job", Job.class);
  }

  public Endpoint endpoint(String id) throws Exception {
    Endpoint e = require(store.get("endpoint", id, Endpoint.class), "存储连接不存在");
    e.password = store.decrypt(e.password);
    return e;
  }

  public Job job(String id) throws Exception {
    return require(store.get("job", id, Job.class), "同步任务不存在");
  }

  public static <T> T require(T value, String message) {
    if (value == null) throw new IllegalArgumentException(message);
    return value;
  }

  public synchronized Endpoint saveEndpoint(Endpoint e, String actor) throws Exception {
    text(e.name, "连接名称");
    text(e.root, "根目录");
    if (e.agentId == null) e.agentId = "";
    if (e.agentRoot == null) e.agentRoot = "";
    if (!e.agentId.isBlank()) {
      var device = store.get("agent", e.agentId, com.oadig.syncsrv.modules.discovery.AgentService.Device.class);
      if (device == null || !device.status.equals("APPROVED")) throw new IllegalArgumentException("请选择已授权的执行设备");
      text(e.agentRoot, "设备本地目录");
      e.agentRoot = devicePath(e.agentRoot);
    } else e.agentRoot = "";
    if (!List.of("LOCAL", "SMB", "FTP", "SFTP", "WEBDAV").contains(e.protocol))
      throw new IllegalArgumentException("不支持的协议");
    if (e.port < 0 || e.port > 65535) throw new IllegalArgumentException("端口范围不正确");
    if (e.protocol.equals("LOCAL")) {
      Path p = Path.of(e.root);
      if (!p.isAbsolute() || !Files.isDirectory(p))
        throw new IllegalArgumentException("本地根目录必须是已存在的绝对目录");
      e.root = p.toRealPath().toString();
      if (StoragePaths.overlap(e.root, store.directory.toString()))
        throw new IllegalArgumentException("存储目录不能包含服务数据目录");
    } else {
      text(e.host, "主机地址");
      if (e.protocol.equals("WEBDAV")) {
        try (Storage ignored = StorageFactory.open(e)) {}
      } else if (e.host.contains("/") || e.host.contains("@"))
        throw new IllegalArgumentException("主机地址不应包含路径或凭据");
      String relative = e.root.replaceAll("^/+|/+$", "");
      StoragePaths.relative(relative);
      if (e.protocol.equals("SMB") && relative.isBlank())
        throw new IllegalArgumentException("SMB 根目录须包含共享名称");
      if (e.protocol.equals("SFTP") && !e.hostKey.matches("SHA256:[A-Za-z0-9+/]{43}=?"))
        throw new IllegalArgumentException("请输入服务器 SHA256 主机密钥指纹");
    }
    Endpoint old = e.id.isBlank() ? null : endpoint(e.id);
    if (old == null) {
      e.id = Ids.next();
      e.createdAt = Ids.now();
    } else {
      e.createdAt = old.createdAt;
      if (e.password.isEmpty()) e.password = old.password;
      boolean used =
          jobs().stream()
              .anyMatch(j -> List.of(j.sourceId, j.targetId, j.archiveId).contains(e.id));
      if ((used || restoreUses(e.id)) && !location(e).equals(location(old)))
        throw new IllegalArgumentException("连接已被任务使用，不能更换存储位置，请新建连接和任务");
    }
    if (old != null && (!old.agentId.equals(e.agentId) || !old.agentRoot.equals(e.agentRoot))) {
      for (var command : store.list("device-pending", com.oadig.syncsrv.modules.discovery.DeviceExecution.Command.class)) {
        var work = new com.oadig.syncsrv.modules.discovery.DeviceExecution(store).work(command);
        if ((work.source() != null && work.source().endpoint().id.equals(e.id)) ||
            (work.target() != null && work.target().endpoint().id.equals(e.id)))
          throw new IllegalArgumentException("此连接仍有执行端任务待确认，不能更改设备映射");
      }
    }
    boolean used = jobs().stream().anyMatch(j -> List.of(j.sourceId,j.targetId,j.archiveId).contains(e.id));
    if (used && !e.agentId.isBlank()) {
      for (Job j : jobs()) {
        validateSourceSeparation(j, e);
        for (String id : List.of(j.sourceId,j.targetId,j.archiveId))
          if (!id.isBlank() && !id.equals(e.id) && overlap(e, endpoint(id)))
            throw new IllegalArgumentException("设备本地目录与已有任务的存储目录重叠");
      }
    }
    Endpoint encrypted = Json.read(Json.write(e), Endpoint.class);
    encrypted.password = store.encrypt(e.password);
    store.commit(actor, "endpoint.saved", e.id, e.safe(), put("endpoint", e.id, encrypted));
    return e.safe();
  }

  public synchronized void deleteEndpoint(String id, String actor) throws Exception {
    endpoint(id);
    if (restoreUses(id)
        || jobs().stream().anyMatch(j -> List.of(j.sourceId, j.targetId, j.archiveId).contains(id)))
      throw new IllegalArgumentException("连接正在被任务使用");
    store.commit(actor, "endpoint.deleted", id, Map.of(), remove("endpoint", id));
  }

  public Map<String, Object> probe(String id, String actor) throws Exception {
    Endpoint e = endpoint(id);
    try (Storage s = StorageFactory.open(e)) {
      Storage.Item root = s.stat("");
      if (root == null || !root.directory() || root.symbolic())
        throw new IllegalArgumentException("根目录不可访问或不是普通目录");
      int count = s.list("").size();
      store.commit(actor, "endpoint.probed", id, Map.of("result", "READABLE", "entries", count));
      Map<String,Object> result = new LinkedHashMap<>();
      result.put("readable", true); result.put("entries", count); result.put("capabilities", s.capabilities());
      if (!e.agentId.isBlank()) {
        var executor = new com.oadig.syncsrv.modules.discovery.DeviceExecution(store);
        if (!executor.available(e.agentId)) throw new IllegalArgumentException("协议连接可读，但执行设备离线或版本不支持存储执行");
        var bound = executor.bind(e, e.agentId);
        var run = new Run(); run.id = Ids.next(); run.actor = actor;
        var task = new com.oadig.syncsrv.modules.transfer.TaskContext(run, new com.oadig.syncsrv.modules.transfer.BandwidthLimiter(0), 0);
        String actual = executor.hash(new Storage.Location(e,bound.marker()),64,task);
        if (!bound.markerHash().equals(actual)) throw new IllegalArgumentException("设备本地目录与存储连接不是同一位置");
        result.put("deviceMappingVerified", true); result.put("agentId", e.agentId);
      }
      return result;
    } catch (Exception ex) {
      Exception failure = e.protocol.equals("SMB")
          ? SmbFailure.from(e, SmbFailure.Stage.DIRECTORY, ex) : ex;
      Object detail = failure instanceof SmbFailure smb
          ? smb.details() : Map.of("reason", failure.getClass().getSimpleName());
      store.commit(actor, "endpoint.probe_failed", id, detail);
      throw failure;
    }
  }

  public synchronized Job saveJob(Job j, String actor) throws Exception {
    if(j.enabled&&store.get("recovery-state","pending",Map.class)!=null)throw new IllegalArgumentException("请先离线执行 verify-state-files 完成 B/C 灾备校验");
    text(j.name, "任务名称");
    if (!List.of("CONNECTION", "AGENT").contains(j.sourceMode))
      throw new IllegalArgumentException("不支持的源端方式");
    if (j.sourceMode.equals("AGENT")) {
      j.sourceId = "";
      j.discoveryMode = "EVENTS";
    }
    if (!List.of("SCAN", "EVENTS").contains(j.discoveryMode))
      throw new IllegalArgumentException("不支持的发现方式");
    if (j.discoveryMode.equals("EVENTS")) {
      text(j.agentRoot, "源端本地监听路径");
      if (!(j.agentRoot.startsWith("/") || j.agentRoot.matches("^[A-Za-z]:[\\\\/].*")))
        throw new IllegalArgumentException("源端监听路径必须是绝对路径");
      var device =
          store.get(
              "agent", j.agentId, com.oadig.syncsrv.modules.discovery.AgentService.Device.class);
      if (device == null || !device.status.equals("APPROVED"))
        throw new IllegalArgumentException("请选择已授权的源端设备");
    }
    if (j.reconcileSeconds < 300 || j.reconcileSeconds > 31536000)
      throw new IllegalArgumentException("独立校准间隔须在 300 秒到 1 年之间");
    if (j.intervalSeconds < (j.discoveryMode.equals("EVENTS") ? 1 : 30)
        || j.intervalSeconds > 31536000)
      throw new IllegalArgumentException("执行间隔不正确：事件模式至少 1 秒，扫描模式至少 30 秒");
    if (!Double.isFinite(j.bandwidthMiB) || j.bandwidthMiB < 0 || j.bandwidthMiB > 100000)
      throw new IllegalArgumentException("限速值不正确");
    if (j.archiveThreads < 1 || j.archiveThreads > 16)
      throw new IllegalArgumentException("中心文件并发数必须为 1 至 16");
    if (j.maxTransferThreads < 1 || j.maxTransferThreads > 16)
      throw new IllegalArgumentException("自动并发上限必须为 1 至 16");
    if (j.transferThreads < 1 || j.transferThreads > 16)
      throw new IllegalArgumentException("并发文件数须为 1 至 16");
    List<Endpoint> selected = new ArrayList<>();
    if (!j.sourceMode.equals("AGENT")) selected.add(endpoint(j.sourceId));
    if (j.sourceMode.equals("AGENT")
        && j.id.isBlank()
        && endpoint(j.targetId).protocol.equals("LOCAL"))
      throw new IllegalArgumentException("源端直传备份库请使用源端与中心均可访问的 SMB/SFTP/FTP/WebDAV 连接");
    selected.add(endpoint(j.targetId));
    selected.add(endpoint(j.archiveId));
    for (Endpoint e : selected) validateSourceSeparation(j, e);
    for (int a = 0; a < selected.size(); a++)
      for (int b = a + 1; b < selected.size(); b++)
        if (overlap(selected.get(a), selected.get(b)))
          throw new IllegalArgumentException("源目录、备份库、归档库必须使用互不包含的独立目录");
    Job old = j.id.isBlank() ? null : job(j.id);
    if (old != null
        && !old.agentId.isBlank()
        && (!old.agentId.equals(j.agentId) || !old.agentRoot.equals(j.agentRoot)))
      throw new IllegalArgumentException("已绑定任务不能更换源端设备或监听根；请新建任务以建立独立基线");
    if (old != null
        && (!old.sourceMode.equals(j.sourceMode)
            || !old.sourceId.equals(j.sourceId)
            || !old.targetId.equals(j.targetId)
            || !old.archiveId.equals(j.archiveId)))
      throw new IllegalArgumentException("已创建任务不能更换 源目录、备份库与归档库，历史版本依赖这些位置");
    for (Job other : jobs())
      if (!other.id.equals(j.id)) {
        for (Endpoint e : selected) {
          validateSourceSeparation(other, e);
          for (String otherId : List.of(other.sourceId, other.targetId, other.archiveId))
            if (!otherId.isBlank()) {
              Endpoint otherEndpoint = endpoint(otherId);
              validateSourceSeparation(j, otherEndpoint);
              if (overlap(e, otherEndpoint))
                throw new IllegalArgumentException("不同任务的存储目录不能重叠");
            }
        }
      }
    if (old == null) {
      j.id = Ids.next();
      j.createdAt = Ids.now();
    } else j.createdAt = old.createdAt;
    synchronized (store) {
      if (j.discoveryMode.equals("EVENTS")) {
        var device =
            store.get(
                "agent", j.agentId, com.oadig.syncsrv.modules.discovery.AgentService.Device.class);
        if (device == null || !device.status.equals("APPROVED"))
          throw new IllegalArgumentException("源端授权已变化，请刷新后重新配置");
      }
      List<com.oadig.syncsrv.platform.persistence.Store.Change> changes = new ArrayList<>();
      changes.add(put("job", j.id, j));
      Set<String> agentIds = new HashSet<>();
      if (old != null && !old.agentId.isBlank()) agentIds.add(old.agentId);
      if (!j.agentId.isBlank()) agentIds.add(j.agentId);
      for (String id : agentIds) {
        var agent =
            store.get("agent", id, com.oadig.syncsrv.modules.discovery.AgentService.Device.class);
        if (agent != null) {
          agent.configVersion++;
          changes.add(put("agent", id, agent));
        }
      }
      store.commit(
          actor,
          "job.saved",
          j.id,
          j,
          changes.toArray(com.oadig.syncsrv.platform.persistence.Store.Change[]::new));
      return j;
    }
  }

  private boolean restoreUses(String id) throws Exception {
    return store.list("run", Run.class).stream()
        .anyMatch(
            r ->
                r.restoreEndpointId.equals(id)
                    && List.of("QUEUED", "RUNNING", "PAUSING", "PAUSED", "INTERRUPTED", "FAILED")
                        .contains(r.status));
  }

  public void validateRestoreEndpoint(Endpoint endpoint, String relative) throws Exception {
    StoragePaths.relative(relative);
    if (relative.equalsIgnoreCase(".syncsrv")
        || relative.toLowerCase(Locale.ROOT).startsWith(".syncsrv/"))
      throw new IllegalArgumentException("不能恢复到保留目录");
    Endpoint destination = Json.read(Json.write(endpoint), Endpoint.class);
    destination.root = StoragePaths.join(endpoint.root, relative);
    if (!destination.agentId.isBlank()) destination.agentRoot = StoragePaths.join(endpoint.agentRoot, relative);
    if (destination.protocol.equals("LOCAL")
        && StoragePaths.overlap(
            StoragePaths.canonical(Path.of(destination.root)).toString(),
            store.directory.toString())) throw new IllegalArgumentException("恢复目录不能包含服务数据");
    for (Job job : jobs()) {
      validateSourceSeparation(job, destination);
      for (String id : List.of(job.sourceId, job.targetId, job.archiveId))
        if (!id.isBlank() && overlap(destination, endpoint(id)))
          throw new IllegalArgumentException("恢复库必须与所有任务的 源目录、备份库与归档库目录分离");
    }
  }

  public void validateRestore(Path destination) throws Exception {
    String d = StoragePaths.canonical(destination).toString();
    if (StoragePaths.overlap(d, store.directory.toString()))
      throw new IllegalArgumentException("恢复目录不能包含服务数据");
    for (Endpoint e : store.list("endpoint", Endpoint.class))
      if (e.protocol.equals("LOCAL") && StoragePaths.overlap(d, e.root))
        throw new IllegalArgumentException("恢复目录必须与所有已配置的本地存储分离");
  }

  private static void validateSourceSeparation(Job job, Endpoint e) throws Exception {
    boolean watched = job.sourceMode.equals("AGENT") || job.discoveryMode.equals("EVENTS");
    if (watched && !e.id.equals(job.sourceId) && !e.agentId.isBlank() && e.agentId.equals(job.agentId)
        && StoragePaths.overlap(devicePath(job.agentRoot).toLowerCase(Locale.ROOT), devicePath(e.agentRoot).toLowerCase(Locale.ROOT)))
      throw new IllegalArgumentException("同设备的源目录与备份、恢复目录必须互不包含");
  }

  private static String devicePath(String path) {
    if (path == null || !(path.startsWith("/") || path.matches("^[A-Za-z]:[\\\\/].*")) || path.contains("\0"))
      throw new IllegalArgumentException("设备目录必须是绝对路径");
    String normal = path.replace('\\','/').replaceAll("/+$", "");
    for (String part : normal.split("/")) if (part.equals(".") || part.equals(".."))
      throw new IllegalArgumentException("设备目录不能包含相对路径段");
    return normal.isEmpty() ? "/" : normal;
  }

  public static boolean overlap(Endpoint a, Endpoint b) throws Exception {
    if (!a.agentId.isBlank() && a.agentId.equals(b.agentId)
        && StoragePaths.overlap(devicePath(a.agentRoot).toLowerCase(Locale.ROOT), devicePath(b.agentRoot).toLowerCase(Locale.ROOT))) return true;
    String[] x = location(a).split("\\|", 2), y = location(b).split("\\|", 2);
    return x[0].equalsIgnoreCase(y[0])
        && StoragePaths.overlap(x[1].toLowerCase(Locale.ROOT), y[1].toLowerCase(Locale.ROOT));
  }

  public static String location(Endpoint e) throws Exception {
    if (e.protocol.equals("LOCAL")) return "LOCAL|" + StoragePaths.canonical(Path.of(e.root));
    String root = e.root.replaceAll("^/+|/+$", "");
    if (e.protocol.equals("WEBDAV")) {
      URI u = URI.create(e.host);
      int port =
          e.port > 0
              ? e.port
              : (u.getPort() > 0 ? u.getPort() : (u.getScheme().equals("https") ? 443 : 80));
      return "WEBDAV:"
          + u.getHost()
          + ":"
          + port
          + "|"
          + u.getPath().replaceAll("/+$", "")
          + "/"
          + root;
    }
    int port =
        e.port > 0
            ? e.port
            : switch (e.protocol) {
              case "SMB" -> 445;
              case "SFTP" -> 22;
              default -> 21;
            };
    return e.protocol + ":" + e.host + ":" + port + "|/" + root;
  }

  private static void text(String value, String label) {
    if (value == null || value.isBlank() || value.length() > 1024 || value.contains("\0"))
      throw new IllegalArgumentException(label + "不能为空或过长");
  }
}
