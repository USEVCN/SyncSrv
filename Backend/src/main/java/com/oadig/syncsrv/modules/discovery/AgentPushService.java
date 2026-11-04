package com.oadig.syncsrv.modules.discovery;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.sync.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Source-initiated resumable upload to B; publication never changes live B or C. */
public final class AgentPushService {
  public record Begin(
      String jobId, String token, List<String> scopes, int entries, String transferMode) {
    public Begin(String jobId, String token, List<String> scopes, int entries) {
      this(jobId, token, scopes, entries, "LEGACY_RELAY");
    }
  }

  public record Page(String sessionId, int offset, List<Node> entries) {}

  public record FileRequest(String jobId, Node entry) {}

  public record FileStatus(String hash, long offset, boolean ready) {}

  public static class Session {
    public String id, jobId, agentId, status = "UPLOADING", createdAt;
    public List<String> scopes;
    public int total, received;
    public long baseRevision;
    public boolean direct;
    public boolean batchDirect = true;
  }

  public record Blob(String hash, long size, long offset, boolean ready) {}
  public record ManifestPage(int offset, String fingerprint) {}

  private final Store store;
  private final ConfigurationService config;
  private static final Map<Store, Object> LOCKS = Collections.synchronizedMap(new WeakHashMap<>());
  final Object lock;

  public AgentPushService(Store store, ConfigurationService config) {
    this.store = store;
    this.config = config;
    synchronized (LOCKS) { lock = LOCKS.computeIfAbsent(store, ignored -> new Object()); }
  }

  static String draftKind(String session) {
    return Ids.textHash("push-draft:" + session).substring(0, 32);
  }

  private static String pageKind(String session) {
    return Ids.textHash("push-pages:" + session).substring(0, 32);
  }

  private static String blobKind(String job) {
    return Ids.textHash("push-blob:" + job).substring(0, 32);
  }

  private Job job(String agent, String id) throws Exception {
    Job j = config.job(id);
    AgentService.Device d = store.get("agent", agent, AgentService.Device.class);
    if (d == null
        || !d.status.equals("APPROVED")
        || !j.agentId.equals(agent)
        || !j.sourceMode.equals("AGENT")) throw new SecurityException("设备无此源端上传权限");
    if (!j.enabled) throw new IllegalStateException("任务已暂停，变化将保留在源端");
    return j;
  }

  public Session begin(String agent, Begin input) throws Exception {
    synchronized (lock) {
      if (input == null) throw new IllegalArgumentException("上传批次不能为空");
      Job j = job(agent, input.jobId());
      if (input.token() == null
          || !input.token().matches("[a-f0-9]{32}")
          || input.entries() < 0
          || input.scopes() == null
          || input.scopes().isEmpty()
          || input.scopes().size() > 512) throw new IllegalArgumentException("上传批次不正确");
      if (input.transferMode() == null || !List.of("DIRECT", "LEGACY_RELAY").contains(input.transferMode()))
        throw new IllegalArgumentException("上传方式不正确");
      Set<String> scopes = new HashSet<>();
      for (String p : input.scopes()) {
        if (!StoragePaths.relative(p).equals(p) || !scopes.add(p)
            || p.equalsIgnoreCase(".syncsrv") || p.toLowerCase(Locale.ROOT).startsWith(".syncsrv/"))
          throw new IllegalArgumentException("上传范围重复或不正确");
      }
      String id = Ids.textHash(j.id + ":" + input.token());
      Session previous = store.get("push-session", id, Session.class);
      if (previous != null) {
        if (!previous.agentId.equals(agent) || previous.total != input.entries()
            || previous.direct != "DIRECT".equals(input.transferMode())
            || !previous.scopes.equals(input.scopes()))
          throw new IllegalArgumentException("同一上传编号不能用于不同清单");
        return previous;
      }
      AgentSource.State base = AgentSource.state(store, j.id);
      if (base == null && !input.scopes().contains(""))
        throw new IllegalArgumentException("首次上传必须包含完整基线");
      for (Session old : store.list("push-session", Session.class)) {
        if (old.jobId.equals(j.id) && old.status.equals("UPLOADING")) abort(agent, old.id);
        if (!old.status.equals("UPLOADING")
            && java.time.Instant.parse(old.createdAt)
                .isBefore(java.time.Instant.now().minusSeconds(604800)))
          store.commit(
              "system",
              "source.receipt_expired",
              old.jobId,
              Map.of("session", old.id),
              remove("push-session", old.id));
      }
      Session s = new Session();
      s.direct = "DIRECT".equals(input.transferMode());
      s.id = id;
      s.jobId = j.id;
      s.agentId = agent;
      s.scopes = List.copyOf(input.scopes());
      s.total = input.entries();
      s.baseRevision = base == null ? 0 : base.revision();
      s.createdAt = Ids.now();
      store.commit(
          agent,
          "source.upload_started",
          j.id,
          Map.of("session", id, "entries", s.total),
          put("push-session", id, s));
      return s;
    }
  }

  Session session(String agent, String id) throws Exception {
    Session s =
        ConfigurationService.require(store.get("push-session", id, Session.class), "上传会话不存在");
    job(agent, s.jobId);
    if (!s.agentId.equals(agent)) throw new SecurityException("设备无此会话权限");
    return s;
  }

  public Session page(String agent, Page input) throws Exception {
    synchronized (lock) {
      if (input == null) throw new IllegalArgumentException("上传分页不能为空");
      Session s = session(agent, input.sessionId());
      if (!s.status.equals("UPLOADING") || input.entries() == null || input.entries().isEmpty()
          || input.entries().size() > 256 || input.offset() < 0)
        throw new IllegalArgumentException("上传分页不正确");
      Set<String> paths = new HashSet<>();
      for (Node n : input.entries()) {
        AgentSource.validate(n);
        if (!paths.add(n.path())) throw new IllegalArgumentException("清单分页包含重复路径");
        boolean ancestor =
            n.directory() && s.scopes.stream().anyMatch(p -> p.startsWith(n.path() + "/"));
        if (!ancestor && !SyncEngine.within(n.path(), s.scopes))
          throw new IllegalArgumentException("条目超出声明范围");
      }
      String fingerprint = Ids.hash(Json.bytes(input.entries().stream().map(n -> n.version("")).toList()));
      ManifestPage receipt = store.get(pageKind(s.id), Integer.toString(input.offset()), ManifestPage.class);
      if (receipt != null) {
        if (!receipt.fingerprint().equals(fingerprint))
          throw new IllegalArgumentException("同一分页编号不能用于不同清单");
        return s;
      }
      if (input.offset() != s.received || input.entries().size() > s.total - s.received)
        throw new IllegalArgumentException("上传分页顺序不正确，请重建观察批次");
      if (!store.getMany(draftKind(s.id), paths.stream().map(Ids::textHash).toList(), Node.class).isEmpty())
        throw new IllegalArgumentException("清单不同分页包含重复路径");
      List<Store.Change> changes = new ArrayList<>();
      for (Node n : input.entries()) {
        changes.add(put(draftKind(s.id), Ids.textHash(n.path()), n.version("")));
      }
      s.received += input.entries().size();
      changes.add(put("push-session", s.id, s));
      changes.add(put(pageKind(s.id), Integer.toString(input.offset()), new ManifestPage(input.offset(), fingerprint)));
      store.commit(
          agent,
          "source.manifest_page",
          s.jobId,
          Map.of("session", s.id, "received", s.received),
          changes.toArray(Store.Change[]::new));
      return s;
    }
  }

  public FileStatus prepare(String agent, FileRequest input) throws Exception {
    synchronized (lock) {
      Job j = job(agent, input.jobId());
      Node n = input.entry();
      AgentSource.validate(n);
      if (n.directory()) throw new IllegalArgumentException("目录不能上传内容");
      Node installed = new CurrentIndex(store).get(j.id, n.path());
      if (installed != null && TreeScanner.sameContent(installed, n))
        return new FileStatus(n.hash(), n.size(), true);
      Blob state = store.get(blobKind(j.id), n.hash(), Blob.class);
      try (Storage b = AgentSource.staging(store, config, j)) {
        String path = AgentSource.blob(j.id, n.hash());
        Storage.Item item = b.stat(path);
        if (state != null && state.size() != n.size())
          throw new IllegalArgumentException("内容哈希与长度冲突");
        if (state == null
            || (state.ready()
                && item != null
                && !item.directory()
                && !item.symbolic()
                && !digest(b, path).equals(n.hash()))
            || item == null
            || item.symbolic()
            || item.directory()
            || item.size() != state.offset()) {
          state = new Blob(n.hash(), n.size(), 0, false);
          store.commit(
              agent,
              "source.upload_prepared",
              j.id,
              Map.of("hash", n.hash(), "size", n.size()),
              put(blobKind(j.id), n.hash(), state));
        }
        if (n.size() == 0 && !state.ready()) {
          b.mkdirs(StoragePaths.parent(path));
          b.write(path, 0, new ByteArrayInputStream(new byte[0]), 0);
          if (!Ids.hash(new byte[0]).equals(n.hash())) throw new IllegalArgumentException("空文件哈希错误");
          state = new Blob(n.hash(), 0, 0, true);
          store.commit(
              agent,
              "source.content_verified",
              j.id,
              Map.of("hash", n.hash()),
              put(blobKind(j.id), n.hash(), state));
        }
        return new FileStatus(state.hash(), state.offset(), state.ready());
      }
    }
  }

  public FileStatus chunk(
      String agent, String jobId, String hash, long offset, byte[] bytes) throws Exception {
    synchronized (lock) {
      Job j = job(agent, jobId);
      if (!hash.matches("[a-f0-9]{64}") || bytes.length == 0 || bytes.length > 524288 || offset < 0)
        throw new IllegalArgumentException("内容分块参数错误");
      Blob state =
          ConfigurationService.require(store.get(blobKind(j.id), hash, Blob.class), "请先准备上传");
      if (offset > state.offset() || bytes.length > state.size() - offset)
        throw new IllegalArgumentException("上传偏移不正确");
      try (Storage b = AgentSource.staging(store, config, j)) {
        String path = AgentSource.blob(j.id, hash);
        if (offset < state.offset() || state.ready()) {
          if (offset + bytes.length > state.offset()) throw new IllegalArgumentException("重试分块范围不正确");
          try (InputStream in = b.read(path, offset)) {
            if (!Arrays.equals(bytes, in.readNBytes(bytes.length)))
              throw new IOException("已接收分块内容不一致");
          }
          return new FileStatus(hash, state.offset(), state.ready());
        }
        b.mkdirs(StoragePaths.parent(path));
        b.write(path, offset, new ByteArrayInputStream(bytes), bytes.length);
        long next = offset + bytes.length;
        boolean ready = next == state.size();
        if (ready && !digest(b, path).equals(hash)) {
          store.commit(
              agent,
              "source.upload_corrupt",
              j.id,
              Map.of("hash", hash),
              put(blobKind(j.id), hash, new Blob(hash, state.size(), 0, false)));
          throw new IOException("上传校验失败，已要求从头重传");
        }
        Blob saved = new Blob(hash, state.size(), next, ready);
        store.commit(
            agent,
            ready ? "source.content_verified" : "source.chunk_received",
            j.id,
            Map.of("hash", hash, "offset", next),
            put(blobKind(j.id), hash, saved));
        return new FileStatus(hash, next, ready);
      }
    }
  }

  private static String digest(Storage b, String path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = b.read(path, 0)) {
      byte[] buffer = new byte[262144];
      int n;
      while ((n = in.read(buffer)) >= 0) if (n > 0) digest.update(buffer, 0, n);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  public Session finish(String agent, String sessionId) throws Exception {
    Session s = session(agent, sessionId);
    if (s.status.equals("PUBLISHED")) return s;
    if (!s.status.equals("UPLOADING")) throw new IllegalStateException("上传会话已被更新批次取代");
    if (s.received != s.total) throw new IllegalArgumentException("清单未完整上传，禁止提交删除");
    Job j = config.job(s.jobId);
    List<Node> entries = store.list(draftKind(s.id), Node.class);
    if (entries.size() != s.total) throw new IllegalArgumentException("清单存在重复或缺失路径");
    Map<String, Node> verifiedCurrent = new CurrentIndex(store).load(j.id);
    try (Storage b = s.direct ? StorageFactory.open(config.endpoint(j.targetId)) : AgentSource.staging(store, config, j)) {
      List<Node> pending=entries.stream().filter(n->!n.directory() && !TreeScanner.sameContent(n,verifiedCurrent.get(n.path()))).toList();
      if(s.direct) DirectUploadService.verifyReady(store,j.id,pending,b);
      else for (Node n : pending) {
          Blob blob = store.get(blobKind(j.id), n.hash(), Blob.class);
          Storage.Item file = b.stat(AgentSource.blob(j.id, n.hash()));
          if (blob == null || !blob.ready() || blob.size() != n.size() || file == null
              || file.size() != n.size() || file.symbolic()) throw new IOException("文件尚未完整上传: " + n.path());
      }
    }
    synchronized (lock) {
    synchronized (store) {
      s = session(agent, sessionId);
      if (s.status.equals("PUBLISHED")) return s;
      if (!s.status.equals("UPLOADING") || s.received != s.total)
        throw new IllegalStateException("上传会话已被更新批次取代");
      AgentSource.State base = AgentSource.state(store, j.id);
      if ((base == null ? 0 : base.revision()) != s.baseRevision
          || !verifiedCurrent.equals(new CurrentIndex(store).load(j.id)))
        throw new IllegalStateException("备份清单在检查期间变化，请重建观察批次");
      Map<String, Node> before = AgentSource.load(store, j.id), after = new TreeMap<>(before);
      List<String> scopes = s.scopes;
      after.keySet().removeIf(path -> SyncEngine.within(path, scopes));
      for (Node n : entries) after.put(n.path(), n);
      Set<String> folded = new HashSet<>();
      for (Node n : after.values()) {
        if (!folded.add(n.path().toLowerCase(Locale.ROOT)))
          throw new IllegalArgumentException("存在大小写冲突路径");
        String parent = StoragePaths.parent(n.path());
        if (!parent.isEmpty() && (after.get(parent) == null || !after.get(parent).directory()))
          throw new IllegalArgumentException("目录清单不完整");
      }
      List<Store.Change> changes = new ArrayList<>();
      for (String p : before.keySet())
        if (!after.containsKey(p)) changes.add(remove(AgentSource.kind(j.id), Ids.textHash(p)));
      for (Node n : entries) changes.add(put(AgentSource.kind(j.id), Ids.textHash(n.path()), n));
      String now = Ids.now();
      changes.add(
          put(
              "agent-source-state",
              j.id,
              new AgentSource.State(
                  s.baseRevision + 1,
                  s.scopes.contains("") ? now : base.lastFullScan(),
                  now,
                  s.direct)));
      s.status = "PUBLISHED";
      changes.add(put("push-session", s.id, s));
      for (Node n : entries) changes.add(remove(draftKind(s.id), Ids.textHash(n.path())));
      for (ManifestPage page : store.list(pageKind(s.id), ManifestPage.class))
        changes.add(remove(pageKind(s.id), Integer.toString(page.offset())));
      store.commit(
          agent,
          "source.manifest_published",
          j.id,
          Map.of("session", s.id, "entries", after.size(), "scopes", s.scopes),
          changes.toArray(Store.Change[]::new));
    }
    }
    return s;
  }

  public Session abort(String agent, String sessionId) throws Exception {
    synchronized (lock) {
      // A paused/revoked agent must still be able to abandon its own draft. This
      // action never publishes source data or returns storage credentials.
      Session s = ConfigurationService.require(store.get("push-session", sessionId, Session.class), "上传会话不存在");
      if (!s.agentId.equals(agent)) throw new SecurityException("设备无此会话权限");
      if (s.status.equals("PUBLISHED")) return s;
      if (s.status.equals("ABANDONED")) return s;
      List<Store.Change> changes = new ArrayList<>();
      for (Node n : store.list(draftKind(s.id), Node.class))
        changes.add(remove(draftKind(s.id), Ids.textHash(n.path())));
      for (ManifestPage page : store.list(pageKind(s.id), ManifestPage.class))
        changes.add(remove(pageKind(s.id), Integer.toString(page.offset())));
      s.status = "ABANDONED";
      changes.add(put("push-session", s.id, s));
      store.commit(
          agent,
          "source.observation_superseded",
          s.jobId,
          Map.of("session", s.id, "reason", "SOURCE_CHANGED_OR_UPLOAD_FAILED"),
          changes.toArray(Store.Change[]::new));
      return s;
    }
  }

  /** Called only after the single sync writer has completed all file operations. */
  public void cleanup(Job j) throws Exception {
    // Keep NAS I/O outside the global database monitor; upload services share this
    // lock so a new receipt cannot race with cleanup from the backup worker.
    synchronized (lock) {
      DirectUploadService.cleanup(store, config, j);
      List<Blob> blobs = store.list(blobKind(j.id), Blob.class);
      if (blobs.isEmpty()) return;
      Set<String> protectedHashes = new HashSet<>();
      for (Session s : store.list("push-session", Session.class))
        if (s.jobId.equals(j.id) && s.status.equals("UPLOADING"))
          for (Node n : store.list(draftKind(s.id), Node.class))
            if (!n.directory()) protectedHashes.add(n.hash());
      Map<String, Node> current = new CurrentIndex(store).load(j.id);
      for (Node n : AgentSource.load(store, j.id).values())
        if (!n.directory() && !TreeScanner.sameContent(n, current.get(n.path())))
          protectedHashes.add(n.hash());
      try (Storage b = AgentSource.staging(store, config, j)) {
        for (Blob blob : blobs) {
          if (protectedHashes.contains(blob.hash())) continue;
          String path = AgentSource.blob(j.id, blob.hash());
          if (b.stat(path) != null) b.delete(path, false);
          store.commit(
              "system",
              "source.staging_released",
              j.id,
              Map.of("hash", blob.hash()),
              remove(blobKind(j.id), blob.hash()));
        }
      }
    }
  }
}
