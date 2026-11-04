package com.oadig.syncsrv.modules.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oadig.syncsrv.shared.Json;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.core.env.Environment;

/** Root-owned deployment policy is read-only; service-owned HTTPS preferences never widen it. */
final class UpdateSourceConfiguration {
  record Source(URI feed, String channel, String baseRevision, String revision) { }
  interface Audit {
    void record(String actor, String action, Map<String,Object> detail) throws Exception;
    default void verify(JsonNode value) throws Exception { }
    default JsonNode recoverable() throws Exception { return null; }
    default void verifyPending(JsonNode value, JsonNode previous) throws Exception { }
  }
  interface Writer { void write(Path path, JsonNode value) throws Exception; }
  private final Environment environment;
  private final Path state;
  private final Audit audit;
  private final Writer writer;

  UpdateSourceConfiguration(Environment environment, Path directory, Audit audit) {
    this(environment, directory, audit, UpdateSourceConfiguration::atomic);
  }
  UpdateSourceConfiguration(Environment environment, Path directory, Audit audit, Writer writer) {
    this.environment = environment; this.state = directory.toAbsolutePath().normalize().resolve("update-source.json");
    this.audit = audit; this.writer = writer;
  }

  private Source base() throws Exception {
    String manifest = environment.getProperty("syncsrv.updates.manifest", "");
    String allowed = environment.getProperty("syncsrv.updates.allow-http-origin", "");
    if (manifest.isBlank()) {
      Path config = Path.of(environment.getProperty("syncsrv.updates.config", "/etc/syncsrv/update.json"));
      if (Files.exists(config, LinkOption.NOFOLLOW_LINKS)) {
        JsonNode value = read(config);
        manifest = value.hasNonNull("serverUrl") ? CenterUpdateService.manifestForServer(value.path("serverUrl").asText(),
            value.path("manifest").asText(CenterUpdateService.DEFAULT_FEED)) : value.path("manifest").asText();
        allowed = value.path("allowHttpOrigin").asText("");
      } else {
        Path legacy = config.toAbsolutePath().resolveSibling("update.url");
        manifest = Files.exists(legacy, LinkOption.NOFOLLOW_LINKS) ? new String(bytes(legacy, 4096), StandardCharsets.UTF_8).strip() : CenterUpdateService.DEFAULT_FEED;
      }
    }
    URI uri = URI.create(manifest); String channel = ReleaseContract.channel(uri);
    if (!"https".equals(uri.getScheme())) {
      URI origin = URI.create(allowed);
      ReleaseContract.require(CenterUpdateService.privateLiteral(uri.getHost()) && origin.getRawUserInfo() == null
          && origin.getRawQuery() == null && origin.getRawFragment() == null && Set.of("", "/").contains(origin.getRawPath())
          && ReleaseContract.sameOrigin(uri, origin), "HTTP 更新源需要精确的内网来源授权");
    }
    String revision = digest(uri.toASCIIString() + "\n" + allowed);
    return new Source(uri, channel, revision, revision);
  }

  synchronized Source current() throws Exception {
    Source base = base();
    if (!environment.getProperty("syncsrv.updates.manifest", "").isBlank()) return base;
    JsonNode value = saved(base);
    if (value == null) return base;
    String manifest = CenterUpdateService.manifestForServer(value.path("serverUrl").asText(), base.feed().toString());
    return new Source(URI.create(manifest), base.channel(), base.baseRevision(), value.path("revision").asText());
  }

  private JsonNode saved(Source base) throws Exception {
    if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
      safePath(state);
      JsonNode committed = audit.recoverable();
      if (committed == null) { audit.verify(null); return null; }
      validate(committed, base, false);
      audit.verify(committed);
      audit.record("system", "updates.source_export_recovery_requested", Map.of("revision", committed.path("revision").asText(), "reason", "MISSING_EXPORT"));
      writer.write(state, committed);
      audit.record("system", "updates.source_export_recovered", Map.of("revision", committed.path("revision").asText()));
      return committed;
    }
    JsonNode value = read(state);
    validate(value, base, true);
    if ("PENDING".equals(value.path("status").asText())) {
      JsonNode previous = value.get("previous");
      if (previous != null && !previous.isNull()) validate(previous, base, false);
      audit.verifyPending(value, previous);
      audit.record("system", "updates.source_recovery_requested", Map.of("revision", value.path("revision").asText(), "state", previous == null ? Json.MAPPER.nullNode() : previous));
      writer.write(state, previous == null || previous.isNull() ? null : previous);
      audit.record("system", "updates.source_recovered", Map.of("rollback", "RESTORED"));
      return previous == null || previous.isNull() ? null : previous;
    }
    audit.verify(value);
    return value;
  }

  private static void validate(JsonNode value, Source base, boolean pendingAllowed) {
    if (!value.isObject() || !value.path("schema").isInt() || value.path("schema").asInt() != 1
        || !("COMMITTED".equals(value.path("status").asText()) || pendingAllowed && "PENDING".equals(value.path("status").asText()))
        || !base.baseRevision().equals(value.path("baseRevision").asText())
        || !value.path("revision").isTextual() || !value.path("revision").asText().matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("更新源状态与部署策略不一致，请由管理员核对后恢复；未回退到默认来源");
    CenterUpdateService.serverOrigin(value.path("serverUrl").asText());
  }

  synchronized Map<String,String> configuration() throws Exception { return describe(current()); }
  private static Map<String,String> describe(Source source) {
    return Map.of("serverUrl", source.feed().getScheme() + "://" + source.feed().getRawAuthority(),
        "project", "syncsrv", "channel", source.channel(), "revision", source.revision());
  }

  synchronized Map<String,String> save(String server, String expected, String actor) throws Exception {
    if (!environment.getProperty("syncsrv.updates.manifest", "").isBlank())
      throw new IllegalArgumentException("更新源由启动配置固定，请先由部署管理员移除覆盖设置");
    String origin = CenterUpdateService.serverOrigin(server);
    Source previous = current();
    if (!Objects.equals(expected, previous.revision())) throw new IllegalArgumentException("更新地址已变化，请重新读取");
    if (actor == null || actor.isBlank()) throw new IllegalArgumentException("更新源变更缺少操作者");
    JsonNode previousBytes = Files.exists(state, LinkOption.NOFOLLOW_LINKS) ? read(state) : null;
    ObjectNode next = Json.MAPPER.createObjectNode().put("schema", 1).put("status", "COMMITTED")
        .put("serverUrl", origin).put("baseRevision", previous.baseRevision()).put("revision", UUID.randomUUID().toString().replace("-", ""));
    ObjectNode pending = next.deepCopy().put("status", "PENDING");
    pending.set("previous", previousBytes == null ? Json.MAPPER.nullNode() : previousBytes);
    var detail = Map.<String,Object>of("previousSource", describe(previous).get("serverUrl"), "nextSource", origin,
        "previousRevision", previous.revision(), "revision", next.path("revision").asText(), "baseRevision", previous.baseRevision());
    audit.record(actor, "updates.source_change_requested", detail);
    try {
      writer.write(state, pending);
      // Root updater refuses PENDING; a crash before COMMITTED restores the preceding source.
      var committedDetail = new LinkedHashMap<String,Object>(detail); committedDetail.put("state", next);
      audit.record(actor, "updates.source_changed", committedDetail);
      writer.write(state, next);
      return configuration();
    } catch (Exception failure) {
      boolean restored = false;
      try { writer.write(state, previousBytes); restored = true; }
      catch (Exception rollback) { failure.addSuppressed(rollback); }
      try { audit.record(actor, "updates.source_change_failed", Map.of("revision", next.path("revision").asText(), "rollback", restored ? "RESTORED" : "FAILED", "state", restored ? previousBytes == null ? Json.MAPPER.nullNode() : previousBytes : next)); }
      catch (Exception auditFailure) { failure.addSuppressed(auditFailure); }
      throw new IOException(restored ? "更新源未保存，已保留原来源；请核对审计和状态目录权限" : "更新源恢复失败，已停止本次变更，请由管理员检查状态文件", failure);
    }
  }

  static void safePath(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    for (Path p = absolute; p != null; p = p.getParent()) {
      if (!Files.isSymbolicLink(p)) continue;
      boolean systemAlias = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
          && Set.of(Path.of("/var"), Path.of("/etc"), Path.of("/tmp")).contains(p)
          && ((Number) Files.getAttribute(p, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue() == 0
          && p.toRealPath().equals(Path.of("/private").resolve(p.getFileName()));
      if (!systemAlias || p.equals(absolute)) throw new IOException("更新源配置路径不能包含非系统符号链接");
    }
  }

  private static JsonNode read(Path path) throws IOException {
    JsonNode value = Json.MAPPER.readTree(bytes(path, 16384));
    if (value == null || !value.isObject()) throw new IOException("更新源配置文件格式无效");
    return value;
  }
  private static byte[] bytes(Path path, int maximum) throws IOException {
    safePath(path);
    var posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (posix != null) {
      var permissions = posix.readAttributes().permissions();
      if (permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE) || permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE))
        throw new IOException("更新源配置不能允许组或其他用户写入");
    }
    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes(maximum + 1);
      if (bytes.length > maximum) throw new IOException("更新源配置文件过大");
      return bytes;
    }
  }

  static void atomic(Path path, JsonNode value) throws IOException {
    safePath(path);
    if (value == null) { Files.deleteIfExists(path); if (Files.isDirectory(path.getParent())) forceDirectory(path.getParent()); return; }
    Files.createDirectories(path.getParent()); safePath(path);
    Path temp = Files.createTempFile(path.getParent(), ".update-source-", ".json");
    try {
      if (Files.getFileAttributeView(temp, java.nio.file.attribute.PosixFileAttributeView.class) != null)
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
      try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        ByteBuffer data = ByteBuffer.wrap(Json.MAPPER.writeValueAsBytes(value));
        while (data.hasRemaining()) channel.write(data);
        channel.force(true);
      }
      safePath(path);
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      forceDirectory(path.getParent());
    } finally { Files.deleteIfExists(temp); }
  }
  private static void forceDirectory(Path path) throws IOException {
    if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class) != null)
      try (var channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
  }
  static String digest(String text) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }
}
