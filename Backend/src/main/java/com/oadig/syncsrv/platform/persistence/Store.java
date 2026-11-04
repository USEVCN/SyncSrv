package com.oadig.syncsrv.platform.persistence;

import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.spec.*;
import java.sql.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** One writer per database; metadata and signed audit append share one transaction. */
public final class Store implements AutoCloseable {
  public record Change(String kind, String id, Object value, boolean delete) {
    public static Change put(String kind, String id, Object value) {
      return new Change(kind, id, value, false);
    }

    public static Change remove(String kind, String id) {
      return new Change(kind, id, null, true);
    }
  }

  public final Path directory;
  private Connection db;
  private StoreReader reader;
  private HistoryReader historyReader;
  private volatile String writeFailure="";
  private final String databaseUrl;
  private boolean initialized, recovering, closed;
  private long connectionCheckedAt;
  private AuditEvent uncertainCommit;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private final PrivateKey signingKey;
  private final PublicKey publicKey;
  private final byte[] encryptionKey;
  private long expectedSequence;
  private String expectedHead = "";
  private static final String OBJECT_QUERY =
      "SELECT o.id,o.data,s.signature FROM objects o LEFT JOIN object_signatures s "
          + "ON s.kind=o.kind AND s.id=o.id WHERE o.kind=?";

  public Store(Path path) throws Exception {
    Files.createDirectories(path);
    directory = path.toRealPath();
    databaseUrl = MetadataTools.url(directory);
    secure(directory, "rwx------");
    lockChannel =
        FileChannel.open(
            directory.resolve("instance.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = lockChannel.tryLock();
    } catch (OverlappingFileLockException e) {
      lockChannel.close();
      throw new IllegalStateException("数据目录已有服务实例", e);
    }
    if (acquired == null) {
      lockChannel.close();
      throw new IllegalStateException("数据目录已有服务实例");
    }
    lock = acquired;
    Connection opened = null;
    try {
      if (!Files.exists(directory.resolve("metadata.mv.db")) && Files.exists(directory.resolve("audit-private.key")))
        throw new IllegalStateException("已有密钥但缺少 H2 数据库：请恢复完整备份，禁止创建空库");
      opened = DriverManager.getConnection(databaseUrl, "sa", "");
      db = opened;
      H2Database.initialize(db);
      boolean populated;
      try (Statement s = db.createStatement();
          ResultSet r =
              s.executeQuery(
                  "SELECT (SELECT COUNT(*) FROM objects)+(SELECT COUNT(*) FROM audit)")) {
        r.next();
        populated = r.getLong(1) > 0;
      }
      Path privateFile = directory.resolve("audit-private.key");
      Path publicFile = directory.resolve("audit-public.key");
      if (!Files.exists(privateFile)) {
        if (populated) throw new IllegalStateException("审计私钥丢失，禁止重建密钥");
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        secretFile(privateFile, pair.getPrivate().getEncoded());
        secretFile(publicFile, pair.getPublic().getEncoded());
      }
      KeyFactory factory = KeyFactory.getInstance("Ed25519");
      signingKey =
          factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privateFile)));
      publicKey = factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicFile)));
      Path cryptoFile = directory.resolve("credentials.key");
      if (!Files.exists(cryptoFile)) {
        if (populated) throw new IllegalStateException("凭据密钥丢失");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        secretFile(cryptoFile, key);
      }
      encryptionKey = Files.readAllBytes(cryptoFile);
      reader=new StoreReader(this,publicKey,databaseUrl,"sa","");
      historyReader=new HistoryReader(databaseUrl,"sa","",publicKey);
      AuditHead head = verify();
      expectedSequence = head.sequence();
      expectedHead = head.head();
      initialized = true;
      connectionCheckedAt = System.nanoTime();
    } catch (Exception e) {
      if (opened != null) opened.close();
      lock.release();
      lockChannel.close();
      throw e;
    }
  }

  private static void secure(Path p, String permissions) throws Exception {
    if (Files.getFileStore(p).supportsFileAttributeView("posix"))
      Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(permissions));
  }

  /** Reconnect between operations, never replay a transaction whose result is uncertain. */
  private void ensureConnection(boolean force) throws Exception {
    if (closed) throw new SQLException("元数据存储已关闭");
    if (!initialized || recovering) return;
    if (db != null && !db.isClosed()) {
      if (!force && System.nanoTime() - connectionCheckedAt < java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) return;
      try {
        if (db.isValid(2)) { connectionCheckedAt = System.nanoTime(); return; }
      } catch (SQLException ignored) { /* Reacquire ownership before resuming. */ }
    }
    disconnect();
    Connection fresh = DriverManager.getConnection(databaseUrl, "sa", "");
    try {
      H2Database.configure(fresh);
      db = fresh;
      recovering = true;
      AuditHead head = verify();
      boolean unchanged = head.sequence() == expectedSequence && head.head().equals(expectedHead);
      boolean committed = uncertainCommit != null && head.sequence() == uncertainCommit.sequence()
          && head.head().equals(uncertainCommit.hash());
      if (!unchanged && !committed)
        throw new IllegalStateException("断线期间审计链头发生变化，停止写入");
      expectedSequence = head.sequence(); expectedHead = head.head(); uncertainCommit = null;
      connectionCheckedAt = System.nanoTime();
      org.slf4j.LoggerFactory.getLogger(Store.class).info("Metadata database connection recovered; writer lock and audit chain verified");
    } catch (Exception failure) {
      try { fresh.close(); } catch (SQLException close) { failure.addSuppressed(close); }
      db = null;
      throw failure;
    } finally {
      recovering = false;
    }
  }

  private void disconnect() {
    Connection previous = db;
    db = null;
    connectionCheckedAt = 0;
    if (previous != null) try { previous.close(); } catch (SQLException ignored) { }
  }

  private static void secretFile(Path p, byte[] data) throws Exception {
    try (FileChannel f =
        FileChannel.open(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      secure(p, "rw-------");
      f.write(java.nio.ByteBuffer.wrap(data));
      f.force(true);
    }
  }

  synchronized Connection readingConnection() throws Exception { ensureConnection(false);return db; }
  public <T>T readSnapshot(java.util.concurrent.Callable<T> work)throws Exception{return reader.snapshot(work);}
  public <T>T get(String kind,String id,Class<T> type)throws Exception{return reader.get(kind,id,type);}
  public <T>List<T> list(String kind,Class<T> type)throws Exception{return reader.list(kind,type);}
  public <T>Map<String,T> getMany(String kind,Collection<String> ids,Class<T> type)throws Exception{return reader.many(kind,ids,type);}
  public <T>List<T> history(String kind,int limit,int offset,Class<T> type)throws Exception{return historyReader.page(kind,limit,offset,type);}
  public <T>List<T> recent(String kind,int limit,Class<T> type)throws Exception{return history(kind,limit,0,type);}
  public SnapshotSummary nearestSnapshot(String jobId,String before)throws Exception{return historyReader.nearest(jobId,before);}
  public List<Run> runHeads()throws Exception{return reader.runHeads(false);}
  public List<Run> restoreRuns()throws Exception{return reader.runHeads(true);}
  public Map<String,Long> historyCounts()throws Exception{return historyReader.counts();}
  public void quarantine(String reason){writeFailure=reason;}

  public synchronized void commit(
      String actor, String action, String subject, Object detail, Change... changes)
      throws Exception {
    if(!writeFailure.isBlank())throw new SecurityException(writeFailure);
    if(reader.inSnapshot())throw new IllegalStateException("只读快照中禁止写入");
    ensureConnection(false);
    try {
      db.setAutoCommit(false);
      long sequence = 0;
      String previous = "";
      try (Statement s = db.createStatement();
          ResultSet r = s.executeQuery("SELECT seq,hash FROM audit ORDER BY seq DESC LIMIT 1")) {
        if (r.next()) {
          sequence = r.getLong(1);
          previous = r.getString(2);
        }
      }
      if (sequence != expectedSequence || !previous.equals(expectedHead))
        throw new IllegalStateException("运行期间审计链头发生变化，停止写入");
      writeChanges(changes);
      AuditEvent unsigned =
          new AuditEvent(
              sequence + 1,
              Ids.now(),
              actor,
              action,
              subject,
              Json.write(detail),
              previous,
              "",
              "");
      String hash = eventHash(unsigned);
      AuditEvent event =
          new AuditEvent(
              unsigned.sequence(),
              unsigned.time(),
              actor,
              action,
              subject,
              unsigned.detail(),
              previous,
              hash,
              sign(hash));
      try (PreparedStatement s =
          db.prepareStatement("INSERT INTO audit VALUES(?,?,?,?,?,?,?,?,?)")) {
        s.setLong(1, event.sequence());
        s.setString(2, event.time());
        s.setString(3, actor);
        s.setString(4, action);
        s.setString(5, subject);
        s.setString(6, event.detail());
        s.setString(7, previous);
        s.setString(8, hash);
        s.setString(9, event.signature());
        s.executeUpdate();
      }
      uncertainCommit = event;
      db.commit();
      expectedSequence = event.sequence();
      expectedHead = hash;
      uncertainCommit = null;
      db.setAutoCommit(true);
    } catch (Exception e) {
      try { if (db != null) db.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
      disconnect();
      throw e;
    }
  }

  private record Write(Change change, String data, String signature) {}

  /** Bounded multi-row statements avoid two database round trips per object. All pages
   * remain in the caller's audited transaction; mixed puts/deletes retain their order. */
  private void writeChanges(Change[] changes) throws Exception {
    List<Write> page = new ArrayList<>();
    long bytes = 0;
    for (Change change : changes) {
      String data = change.delete() ? "" : Json.write(change.value());
      long size = 512L + 2L * data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      if (!page.isEmpty() && (page.size() >= 128 || bytes + size > 1024 * 1024
          || page.get(0).change().delete() != change.delete())) {
        writePage(page); page.clear(); bytes = 0;
      }
      page.add(new Write(change, data, change.delete() ? "" : sign(objectHash(change.kind(), change.id(), data))));
      bytes += size;
    }
    if (!page.isEmpty()) writePage(page);
  }

  private void writePage(List<Write> page) throws SQLException {
    boolean delete = page.get(0).change().delete();
    for (String table : List.of("objects", "object_signatures")) {
      boolean objects = table.equals("objects");
      String column = objects ? "data" : "signature";
      String sql = delete
          ? "DELETE FROM " + table + " WHERE " + String.join(" OR ", Collections.nCopies(page.size(), "(kind=? AND id=?)"))
          : "MERGE INTO " + table + "(kind,id," + column + ")"
              + " KEY(kind,id) VALUES "
              + String.join(",", Collections.nCopies(page.size(), "(?,?,?)"));
      try (PreparedStatement statement = db.prepareStatement(sql)) {
        int index = 1;
        for (Write item : page) {
          statement.setString(index++, item.change().kind());
          statement.setString(index++, item.change().id());
          if (!delete) statement.setString(index++, objects ? item.data() : item.signature());
        }
        statement.executeUpdate();
      }
    }
  }

  static String objectHash(String kind, String id, String data) {
    return Ids.textHash(kind + "\n" + id + "\n" + data);
  }

  public synchronized List<AuditEvent> events(long after, int limit) throws Exception {
    ensureConnection(false);
    List<AuditEvent> out = new ArrayList<>();
    try (PreparedStatement s =
        db.prepareStatement("SELECT * FROM audit WHERE seq>? ORDER BY seq LIMIT ?")) {
      s.setLong(1, after);
      s.setInt(2, Math.min(Math.max(limit, 1), 1000));
      try (ResultSet r = s.executeQuery()) {
        while (r.next())
          out.add(
              new AuditEvent(
                  r.getLong(1),
                  r.getString(2),
                  r.getString(3),
                  r.getString(4),
                  r.getString(5),
                  r.getString(6),
                  r.getString(7),
                  r.getString(8),
                  r.getString(9)));
      }
    }
    return out;
  }

  public synchronized AuditHead verify() throws Exception {
    ensureConnection(false);
    long seq = 0;
    String previous = "";
    while (true) {
      List<AuditEvent> page = events(seq, 500);
      if (page.isEmpty()) break;
      for (AuditEvent e : page) {
        if (e.sequence() != seq + 1
            || !e.previous().equals(previous)
            || !e.hash().equals(eventHash(e))
            || !validSignature(publicKey, e.hash(), e.signature()))
          throw new IllegalStateException("审计链校验失败，事件 #" + e.sequence());
        seq = e.sequence();
        previous = e.hash();
      }
    }
    return new AuditHead(seq, previous, publicKey(), "LOCAL_SIGNED_CHAIN");
  }

  public synchronized AuditHead head() {
    return new AuditHead(expectedSequence, expectedHead, publicKey(), "LOCAL_SIGNED_CHAIN");
  }

  public synchronized String databaseEngine() throws Exception {
    ensureConnection(false);
    return db.getMetaData().getDatabaseProductName();
  }

  public synchronized boolean healthy() throws Exception {
    ensureConnection(true);
    return true;
  }

  public EvidenceHeader header(AuditHead head) throws Exception {
    return new EvidenceHeader("syncsrv-java-audit-v1", head, sign(Ids.hash(Json.bytes(head))));
  }

  public static String eventHash(AuditEvent e) {
    return Ids.hash(
        Json.bytes(
            new AuditEvent(
                e.sequence(),
                e.time(),
                e.actor(),
                e.action(),
                e.subject(),
                e.detail(),
                e.previous(),
                "",
                "")));
  }

  private String sign(String value) throws Exception {
    Signature s = Signature.getInstance("Ed25519");
    s.initSign(signingKey);
    s.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(s.sign());
  }

  public static boolean validSignature(PublicKey key, String value, String signature)
      throws Exception {
    Signature s = Signature.getInstance("Ed25519");
    s.initVerify(key);
    s.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return s.verify(Base64.getDecoder().decode(signature));
  }

  public String publicKey() {
    return Base64.getEncoder().encodeToString(publicKey.getEncoded());
  }

  public String encrypt(String plain) throws Exception {
    if (plain == null || plain.isEmpty()) return "";
    byte[] iv = new byte[12];
    new SecureRandom().nextBytes(iv);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(encryptionKey, "AES"),
        new GCMParameterSpec(128, iv));
    byte[] encrypted = c.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return Base64.getEncoder()
        .encodeToString(
            java.nio.ByteBuffer.allocate(iv.length + encrypted.length)
                .put(iv)
                .put(encrypted)
                .array());
  }

  public String decrypt(String encoded) throws Exception {
    if (encoded == null || encoded.isEmpty()) return "";
    byte[] data = Base64.getDecoder().decode(encoded);
    if (data.length < 28) throw new IllegalArgumentException("凭据损坏");
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(encryptionKey, "AES"),
        new GCMParameterSpec(128, Arrays.copyOf(data, 12)));
    return new String(
        c.doFinal(data, 12, data.length - 12), java.nio.charset.StandardCharsets.UTF_8);
  }

  @Override
  public synchronized void close() throws Exception {
    if (closed) return;
    closed = true;
    try {
      if (db != null) db.close();
    } finally {
      lock.release();
      lockChannel.close();
    }
  }
}
