package com.oadig.syncsrv.modules.discovery;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.Job;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AgentService {
  public static class Device {
    public String id = "",
        name = "",
        platform = "",
        arch = "",
        version = "",
        publicKey = "",
        status = "PENDING",
        createdAt = "",
        lastSeen = "",
        capability = "",
        error = "";
    public long configVersion,
        appliedVersion,
        pendingEvents,
        databaseBytes,
        memoryBytes,
        uploadedBytes,
        uploadedFiles;
    public Double cpuPercent;
    public List<String> watchingJobs = List.of();
    public AgentBacklog.Summary backlog;
  }

  public record Invitation(String tokenHash, String expiresAt, String deviceId, String publicKey) {}

  public record Pairing(String token, String expiresAt) {}

  public record Enrollment(
      String token,
      String name,
      String platform,
      String arch,
      String version,
      String publicKey,
      String signature) {}

  public record Heartbeat(
      long appliedVersion,
      long pendingEvents,
      long databaseBytes,
      long memoryBytes,
      long uploadedBytes,
      long uploadedFiles,
      Double cpuPercent,
      String capability,
      String error,
      List<String> watchingJobs,
      String version) {
    public Heartbeat(long appliedVersion, long pendingEvents, long databaseBytes, long memoryBytes,
        long uploadedBytes, long uploadedFiles, Double cpuPercent, String capability, String error,
        List<String> watchingJobs) {
      this(appliedVersion, pendingEvents, databaseBytes, memoryBytes, uploadedBytes, uploadedFiles,
          cpuPercent, capability, error, watchingJobs, null);
    }
  }

  public record Root(
      String jobId,
      String path,
      int reconcileSeconds,
      String sourceMode,
      boolean enabled,
      int intervalSeconds,
      double bandwidthMiB,
      boolean fullScanRequired,
      int transferThreads, boolean adaptiveTransfers, int maxTransferThreads) {}

  public record Configuration(long version, String status, List<Root> roots, double globalBandwidthMiB) {
    public Configuration(long version,String status,List<Root> roots) { this(version,status,roots,0); }
  }

  private String minimumVersion="";
  public void minimumVersion(String version){minimumVersion=version;}
  private final Store store;
  private final ConfigurationService config;
  private final ChangeInbox inbox;
  private final Map<String, Long> nonces = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicLong lastNonceSweep =
      new java.util.concurrent.atomic.AtomicLong();

  public AgentService(Store store, ConfigurationService config) {
    this.store = store;
    this.config = config;
    inbox = new ChangeInbox(store);
  }

  public List<Device> devices() throws Exception {
    List<Device> devices = store.list("agent", Device.class);
    var summaries = new AgentBacklog(store, config).summaries();
    for (Device device : devices) device.backlog = summaries.getOrDefault(device.id,
        new AgentBacklog.Summary(0, 0, 0, 0, ""));
    return devices;
  }

  public Device device(String id) throws Exception {
    return ConfigurationService.require(store.get("agent", id, Device.class), "源端设备不存在");
  }

  public Pairing invitation(String actor) throws Exception {
    byte[] raw = new byte[32];
    new SecureRandom().nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    String expires = Instant.now().plusSeconds(900).toString();
    String hash = Ids.textHash(token);
    store.commit(
        actor,
        "agent.invitation_created",
        hash,
        Map.of("expiresAt", expires),
        put("agent-invitation", hash, new Invitation(hash, expires, "", "")));
    return new Pairing(token, expires);
  }

  public Device enroll(Enrollment e) throws Exception {
    if (e == null) throw new IllegalArgumentException("配对信息不能为空");
    if (e.token() == null
        || e.token().length() > 128
        || e.publicKey() == null
        || e.publicKey().length() > 256) throw new IllegalArgumentException("配对信息不正确");
    for (String value : new String[] {e.name(), e.platform(), e.arch(), e.version()})
      if (value == null || value.isBlank() || value.length() > 120)
        throw new IllegalArgumentException("设备信息不正确");
    PublicKey key =
        KeyFactory.getInstance("Ed25519")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(e.publicKey())));
    if (!verify(key, e.token() + "\n" + e.publicKey() + "\n" + e.name(), e.signature()))
      throw new SecurityException("源端密钥证明失败");
    synchronized (store) {
      String hash = Ids.textHash(e.token());
      Invitation invite = store.get("agent-invitation", hash, Invitation.class);
      if (invite == null || Instant.now().isAfter(Instant.parse(invite.expiresAt())))
        throw new SecurityException("配对凭据无效或已过期");
      if (!invite.deviceId().isEmpty()) {
        if (!invite.publicKey().equals(e.publicKey())) throw new SecurityException("配对凭据已使用");
        return device(invite.deviceId());
      }
      Device d = new Device();
      d.id = Ids.next();
      d.name = e.name();
      d.platform = e.platform();
      d.arch = e.arch();
      d.version = e.version();
      d.publicKey = e.publicKey();
      d.createdAt = Ids.now();
      store.commit(
          "enrollment",
          "agent.registered",
          d.id,
          Map.of("name", d.name, "platform", d.platform),
          put("agent", d.id, d),
          put(
              "agent-invitation",
              hash,
              new Invitation(hash, invite.expiresAt(), d.id, d.publicKey)));
      return d;
    }
  }

  public Device authorize(String id, boolean approve, String actor) throws Exception {
    synchronized (store) {
      Device d = device(id);
      d.status = approve ? "APPROVED" : "REVOKED";
      d.configVersion++;
      store.commit(
          actor,
          approve ? "agent.approved" : "agent.revoked",
          id,
          Map.of("status", d.status),
          put("agent", id, d));
      return d;
    }
  }

  public Device authenticate(
      String id,
      String method,
      String path,
      String time,
      String nonce,
      String signature,
      byte[] body)
      throws Exception {
    if (id == null
        || time == null
        || nonce == null
        || signature == null
        || !id.matches("[a-f0-9]{32}")
        || !nonce.matches("[a-f0-9]{32}")) throw new SecurityException("缺少源端签名");
    long timestamp;
    try {
      timestamp = Long.parseLong(time);
    } catch (NumberFormatException ex) {
      throw new SecurityException("源端时间格式错误");
    }
    long now = Instant.now().getEpochSecond();
    if (timestamp < now - 120 || timestamp > now + 120)
      throw new SecurityException("源端时钟偏差超过 120 秒");
    Device d = device(id);
    // A revoked device still needs its empty configuration to stop cached watches,
    // and signed cleanup/terminal receipts to settle durable in-flight commands.
    boolean controlOnly = "GET".equals(method) && "/agent/v1/config".equals(path)
        || "POST".equals(method) && List.of("/agent/v1/heartbeat", "/agent/v1/push/abort",
            "/agent/v1/storage/report").contains(path);
    if (d.status.equals("REVOKED") && !controlOnly) throw new SecurityException("源端授权已撤销");
    PublicKey key =
        KeyFactory.getInstance("Ed25519")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(d.publicKey)));
    String message = method + "\n" + path + "\n" + time + "\n" + nonce + "\n" + Ids.hash(body);
    if (!verify(key, message, signature)) throw new SecurityException("源端签名不正确");
    if(!controlOnly&&!minimumVersion.isBlank()&&!Versions.stableAtLeast(d.version,minimumVersion))throw new SecurityException("请先升级源端到 "+minimumVersion);
    long lastSweep = lastNonceSweep.get();
    if (now - lastSweep >= 30 && lastNonceSweep.compareAndSet(lastSweep, now))
      nonces.entrySet().removeIf(e -> e.getValue() < now - 240);
    if (nonces.size() > 100000) throw new IllegalStateException("源端请求过于频繁");
    if (nonces.putIfAbsent(id + ":" + nonce, timestamp) != null)
      throw new SecurityException("请求已使用，请重新签名重试");
    return d;
  }

  private static boolean verify(PublicKey key, String text, String encoded) throws Exception {
    if (encoded == null || encoded.length() > 128) return false;
    try {
      Signature s = Signature.getInstance("Ed25519");
      s.initVerify(key);
      s.update(text.getBytes(StandardCharsets.UTF_8));
      return s.verify(Base64.getDecoder().decode(encoded));
    } catch (IllegalArgumentException | SignatureException e) {
      return false;
    }
  }

  public Configuration configuration(String id) throws Exception {
    Device d = device(id);
    if("APPROVED".equals(d.status)&&!minimumVersion.isBlank()&&!Versions.stableAtLeast(d.version,minimumVersion))return new Configuration(d.configVersion,"UPDATE_REQUIRED",List.of());
    List<Root> roots = new ArrayList<>();
    if (d.status.equals("APPROVED"))
      for (Job j : config.jobs())
        if (j.discoveryMode.equals("EVENTS") && j.agentId.equals(id))
          roots.add(
              new Root(
                  j.id,
                  j.agentRoot,
                  j.reconcileSeconds,
                  j.sourceMode,
                  j.enabled,
                  j.intervalSeconds,
                  j.bandwidthMiB,
                  j.sourceMode.equals("AGENT") && AgentSource.needsFull(store, j),
                  j.transferThreads, j.adaptiveTransfers, j.maxTransferThreads));
    var settings = store.get("settings", "global", com.oadig.syncsrv.modules.jobs.TaskManager.Settings.class);
    return new Configuration(d.configVersion, d.status, roots, settings == null ? 0 : settings.bandwidthMiB());
  }

  public Device heartbeat(String id, Heartbeat h) throws Exception {
    if (h == null) throw new IllegalArgumentException("心跳不能为空");
    if (h.version() != null && !h.version().matches("[A-Za-z0-9._+-]{1,64}"))
      throw new IllegalArgumentException("源端版本不正确");
    if (h.appliedVersion() < 0
        || h.pendingEvents() < 0
        || h.databaseBytes() < 0
        || h.memoryBytes() < 0
        || h.uploadedBytes() < 0
        || h.uploadedFiles() < 0
        || (h.cpuPercent() != null && (!Double.isFinite(h.cpuPercent()) || h.cpuPercent() < 0))
        || h.watchingJobs() == null
        || h.watchingJobs().size() > 1000
        || h.error() == null
        || h.error().length() > 1000
        || h.capability() == null
        || h.capability().length() > 120) throw new IllegalArgumentException("心跳数据不正确");
    synchronized (store) {
      Device d = device(id);
      if (h.appliedVersion() > d.configVersion) throw new IllegalArgumentException("配置版本超前");
      String oldVersion = d.version;
      d.appliedVersion = h.appliedVersion();
      if (h.version() != null) d.version = h.version();
      d.pendingEvents = h.pendingEvents();
      d.databaseBytes = h.databaseBytes();
      d.memoryBytes = h.memoryBytes();
      d.uploadedBytes = h.uploadedBytes();
      d.uploadedFiles = h.uploadedFiles();
      d.cpuPercent = h.cpuPercent();
      d.capability = h.capability();
      d.error = h.error();
      d.watchingJobs = List.copyOf(h.watchingJobs());
      d.lastSeen = Ids.now();
      store.commit(
          id,
          oldVersion.equals(d.version) ? "agent.heartbeat" : "agent.version_changed",
          id,
          Map.of("appliedVersion", d.appliedVersion, "error", d.error, "fromVersion", oldVersion, "version", d.version),
          put("agent", id, d));
      return d;
    }
  }

  public List<String> receive(String id, ChangeInbox.Batch batch) throws Exception {
    if (batch == null) throw new IllegalArgumentException("事件批次不能为空");
    synchronized (store) {
      Device d = device(id);
      if (!d.status.equals("APPROVED")) throw new SecurityException("源端尚未授权");
      if (batch.events() == null || batch.events().isEmpty() || batch.events().size() > 512)
        throw new IllegalArgumentException("事件批次不正确");
      for (ChangeInbox.Event event : batch.events()) {
        if (event == null || event.jobId() == null) throw new IllegalArgumentException("事件缺少任务标识");
        Job j = config.job(event.jobId());
        if (!j.agentId.equals(id) || !j.discoveryMode.equals("EVENTS"))
          throw new SecurityException("源端不属于此任务");
      }
      new SourceChanges(store, config).ready(id, batch);
      return inbox.receive(id, batch);
    }
  }
}
