package com.oadig.syncsrv.modules.discovery;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.sync.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Control-plane metadata only. File payloads travel directly from the Agent to B. */
public final class DirectUploadService {
  public record Open(String sessionId, String publicKey) {}

  public record FileRequest(String sessionId, Node entry) {}

  public record Ticket(String path, boolean ready) {}

  public record Content(String path, Node entry, String verifiedAt) {}

  public record Sealed(String publicKey, String nonce, String ciphertext) {}

  private final Store store;
  private final ConfigurationService config;
  private final AgentPushService push;

  public DirectUploadService(Store store, ConfigurationService config, AgentPushService push) {
    this.store = store;
    this.config = config;
    this.push = push;
  }

  public static String kind(String job) {
    return Ids.textHash("direct-content:" + job).substring(0, 32);
  }

  public static String key(Node n) {
    return Ids.textHash(n.path() + "\n" + n.hash());
  }

  public static String path(String job, Node n) {
    return ".syncsrv/direct/" + Ids.textHash(job) + "/" + key(n);
  }

  private AgentPushService.Session session(String agent, String id) throws Exception {
    var s = push.session(agent, id);
    if (!s.direct || !s.status.equals("UPLOADING")) throw new IllegalStateException("直传会话已结束");
    return s;
  }

  public Sealed open(String agent, Open request) throws Exception {
    var s = session(agent, request.sessionId());
    Endpoint endpoint = config.endpoint(config.job(s.jobId).targetId);
    Object destination = endpoint;
    if (agent.equals(endpoint.agentId) && new DeviceExecution(store).available(agent))
      destination = new DeviceExecution(store).bind(endpoint, agent);
    return seal(destination, s.id, request.publicKey());
  }

  public static Sealed seal(Object value, String context, String publicKey) throws Exception {
    // Ephemeral X25519 + AES-GCM keeps target credentials off plaintext LAN control traffic.
    PublicKey peer =
        KeyFactory.getInstance("X25519")
            .generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
    KeyPair pair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
    KeyAgreement agreement = KeyAgreement.getInstance("X25519");
    agreement.init(pair.getPrivate());
    agreement.doPhase(peer, true);
    byte[] secret = agreement.generateSecret();
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update(secret);
    digest.update(context.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(digest.digest(), "AES"),
        new GCMParameterSpec(128, nonce));
    cipher.updateAAD(context.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new Sealed(
        Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
        Base64.getEncoder().encodeToString(nonce),
        Base64.getEncoder().encodeToString(cipher.doFinal(Json.bytes(value))));
  }

  public record BatchRequest(String sessionId, List<Node> entries) {}

  private List<Node> entries(AgentPushService.Session s, List<Node> input) throws Exception {
    if (input == null || input.isEmpty() || input.size() > 32)
      throw new IllegalArgumentException("直传批次必须包含 1 至 32 个文件");
    Set<String> paths = new HashSet<>();
    List<Node> result = new ArrayList<>();
    for (Node n : input) {
      if (n == null || !paths.add(n.path())) throw new IllegalArgumentException("重复或空文件条目");
      AgentSource.validate(n);
      result.add(n);
    }
    var planned = store.getMany(AgentPushService.draftKind(s.id),
        result.stream().map(n -> Ids.textHash(n.path())).toList(), Node.class);
    for (Node n : result) {
      Node expected=planned.get(Ids.textHash(n.path()));
      if(n.directory() || expected==null || !TreeScanner.sameContent(expected,n))
        throw new IllegalArgumentException("文件不属于当前清单");
    }
    return result;
  }

  public Ticket prepare(String agent, FileRequest request) throws Exception {
    return prepareBatch(agent, new BatchRequest(request.sessionId(), List.of(request.entry()))).get(0);
  }

  public List<Ticket> prepareBatch(String agent, BatchRequest request) throws Exception {
    synchronized (push.lock) {
      var s = session(agent, request.sessionId());
      var nodes = entries(s, request.entries());
      List<Ticket> tickets = new ArrayList<>();
      List<Store.Change> changes = new ArrayList<>();
      if(store.get("current-index",s.jobId,CurrentIndex.Format.class)==null)new CurrentIndex(store).load(s.jobId);
      var installedNodes=store.getMany(CurrentIndex.kind(s.jobId),nodes.stream().map(n->Ids.textHash(n.path())).toList(),Node.class);
      var contents=store.getMany(kind(s.jobId),nodes.stream().map(DirectUploadService::key).toList(),Content.class);
      for (Node n : nodes) {
        Node installed = installedNodes.get(Ids.textHash(n.path()));
        if (TreeScanner.sameContent(installed, n)) {
          tickets.add(new Ticket("", true));
          continue;
        }
        String destination = path(s.jobId, n);
        if (!contents.containsKey(key(n)))
          changes.add(put(kind(s.jobId), key(n), new Content(destination, n, "")));
        tickets.add(new Ticket(destination, false));
      }
      if (!changes.isEmpty()) store.commit(agent, "source.direct_planned", s.jobId,
          Map.of("entries", nodes), changes.toArray(Store.Change[]::new));
      return tickets;
    }
  }

  public Ticket complete(String agent, FileRequest request) throws Exception {
    return completeBatch(agent, new BatchRequest(request.sessionId(), List.of(request.entry()))).get(0);
  }

  public List<Ticket> completeBatch(String agent, BatchRequest request) throws Exception {
    AgentPushService.Session s;
    List<Node> nodes;
    synchronized (push.lock) {
      s = session(agent, request.sessionId());
      nodes = entries(s, request.entries());
    }
    // One connection per batch; network I/O never holds the shared receipt/store lock.
    try (Storage b = StorageFactory.open(config.endpoint(config.job(s.jobId).targetId))) {
      for (Node n : nodes) {
        Storage.Item item = b.stat(path(s.jobId, n));
        if (item == null || item.directory() || item.symbolic() || item.size() != n.size())
          throw new IOException("备份库直传文件不存在或长度不符: " + n.path());
      }
    }
    synchronized (push.lock) {
      // Abort, replacement and authorization changes during I/O invalidate the entire receipt.
      var current = session(agent, request.sessionId());
      entries(current, nodes);
      List<Store.Change> changes = new ArrayList<>();
      List<Ticket> tickets = new ArrayList<>();
      String now = Ids.now();
      var contents=store.getMany(kind(s.jobId),nodes.stream().map(DirectUploadService::key).toList(),Content.class);
      for (Node n : nodes) {
        String destination = path(s.jobId, n);
        Content old = contents.get(key(n));
        if (old == null || old.verifiedAt().isBlank())
          changes.add(put(kind(s.jobId), key(n), new Content(destination, n, now)));
        tickets.add(new Ticket(destination, true));
      }
      // The signed Agent receipt follows per-file read-back SHA-256 verification.
      if (!changes.isEmpty()) store.commit(agent, "source.direct_verified", s.jobId,
          Map.of("entries", nodes), changes.toArray(Store.Change[]::new));
      return tickets;
    }
  }

  public static void verifyReady(Store store,String job,List<Node> nodes,Storage b) throws Exception {
    if(nodes.isEmpty())return;
    var contents=store.getMany(kind(job),nodes.stream().map(DirectUploadService::key).toList(),Content.class);
    Map<String,Storage.Item> listing=null;
    if(nodes.size()>=32) {
      listing=new HashMap<>();
      for(var item:b.list(".syncsrv/direct/"+Ids.textHash(job)))
        if(listing.putIfAbsent(item.name(),item)!=null)throw new IOException("直传目录包含重复条目");
    }
    for(Node n:nodes) {
      Content c=contents.get(key(n));
      Storage.Item item=listing==null ? b.stat(path(job,n)) : listing.get(key(n));
      if(c==null || c.verifiedAt().isBlank() || !TreeScanner.sameContent(c.entry(),n)
          || item==null || item.directory() || item.symbolic() || item.size()!=n.size())
        throw new IOException("源端尚未确认备份库直传校验: "+n.path());
    }
  }

  public static boolean ready(Store store, String job, Node n, Storage b) throws Exception {
    Content c = store.get(kind(job), key(n), Content.class);
    Storage.Item item = b.stat(path(job, n));
    return c != null
        && !c.verifiedAt().isBlank()
        && TreeScanner.sameContent(c.entry(), n)
        && item != null
        && !item.directory()
        && !item.symbolic()
        && item.size() == n.size();
  }

  public static void cleanup(Store store, ConfigurationService config, Job job) throws Exception {
    Set<String> protectedPaths = new HashSet<>();
    for (var s : store.list("push-session", AgentPushService.Session.class))
      if (s.jobId.equals(job.id) && s.direct && s.status.equals("UPLOADING"))
        for (Node n : store.list(AgentPushService.draftKind(s.id), Node.class))
          if (!n.directory()) protectedPaths.add(path(job.id, n));
    Map<String, Node> current = new CurrentIndex(store).load(job.id);
    for (Node n : AgentSource.load(store, job.id).values())
      if (!n.directory() && !TreeScanner.sameContent(n, current.get(n.path())))
        protectedPaths.add(path(job.id, n));
    try (Storage b = StorageFactory.open(config.endpoint(job.targetId))) {
      for (Content c : store.list(kind(job.id), Content.class))
        if (!protectedPaths.contains(c.path())) {
          if (b.stat(c.path()) != null) b.delete(c.path(), false);
          store.commit(
              "system",
              "source.direct_released",
              job.id,
              Map.of("path", c.entry().path()),
              remove(kind(job.id), key(c.entry())));
        }
    }
  }
}
