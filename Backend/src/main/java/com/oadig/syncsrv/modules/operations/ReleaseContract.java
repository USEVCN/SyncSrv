package com.oadig.syncsrv.modules.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oadig.syncsrv.shared.Json;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** RelsHub discovery paths are mutable; publisher-signed identities and digests are authoritative. */
public final class ReleaseContract {
  public static final String SIDECAR = "syncsrv-center-release.json";
  private ReleaseContract() {}
  static final class InvalidContract extends IllegalArgumentException {
    InvalidContract(String message) { super(message); }
  }
  static void require(boolean condition, String message) {
    if (!condition) throw new InvalidContract(message);
  }
  public static String channel(URI feed) {
    require(feed.getRawUserInfo() == null && feed.getRawFragment() == null && feed.getHost() != null
        && Set.of("https", "http").contains(feed.getScheme()), "更新地址格式不正确");
    var match = java.util.regex.Pattern.compile("/api/v1/projects/syncsrv/channels/(stable|beta)/manifest").matcher(feed.getRawPath());
    require(match.matches(), "更新源必须是 syncsrv 的 stable 或 beta 清单");
    require(Set.of("os=linux&arch=x64", "arch=x64&os=linux").contains(Objects.toString(feed.getRawQuery(), "")), "更新源平台必须是 linux/x64");
    return match.group(1);
  }
  private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : uri.getScheme().equals("https") ? 443 : 80; }
  static boolean sameOrigin(URI a, URI b) {
    return a.getScheme().equals(b.getScheme()) && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
  }
  static String id(JsonNode index) {
    String value = index.path("releaseId").asText();
    require(value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "发布 ID 无效");
    return value;
  }
  public static URI artifact(URI feed, JsonNode index, JsonNode file) {
    String channel = channel(feed), release = id(index), path = file.path("url").asText();
    URI uri = feed.resolve(path);
    require(!path.isBlank() && sameOrigin(feed, uri) && uri.getRawUserInfo() == null && uri.getRawQuery() == null
        && uri.getRawFragment() == null && uri.normalize().equals(uri), "制品地址超出更新源");
    String api = "/api/v1/projects/syncsrv/channels/" + channel + "/releases/" + release + "/artifacts/[1-9][0-9]*";
    String publicPath = "/downloads/releases/(?:syncsrv/)?" + release + "/files/[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*";
    require(uri.getRawPath().matches(api) || uri.getRawPath().matches(publicPath), "制品不属于当前通道和发布 ID");
    return uri;
  }
  static JsonNode file(JsonNode index, String kind, String name) {
    JsonNode found = null;
    for (JsonNode f : index.path("files")) if (kind.equals(f.path("kind").asText()) && (name == null || name.equals(f.path("name").asText()))) {
      require(found == null, "发布制品重复"); found = f;
    }
    require(found != null, "发布清单缺少必要制品"); return found;
  }
  private static List<JsonNode> files(JsonNode spec) {
    require(spec.path("files").isArray() && spec.path("files").size() > 0 && spec.path("files").size() <= 100, "制品数量无效");
    var rows = new ArrayList<JsonNode>(); var names = new HashSet<String>();
    for (JsonNode f : spec.path("files")) {
      require(f.path("name").asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,180}") && names.add(f.path("name").asText().toLowerCase(Locale.ROOT)), "制品名称无效或重复");
      require(Set.of("FULL", "INSTALLER", "PATCH").contains(f.path("kind").asText()) && f.path("size").isIntegralNumber()
          && f.path("size").asLong() > 0 && f.path("sha256").asText().matches("[a-f0-9]{64}"), "制品大小或摘要无效");
      rows.add(f);
    }
    rows.sort(Comparator.comparing(f -> f.path("name").asText())); return rows;
  }
  private static void field(StringBuilder out, JsonNode value) {
    if (value == null || value.isMissingNode() || value.isNull()) out.append("~\n");
    else out.append(Base64.getUrlEncoder().withoutPadding().encodeToString(value.asText().getBytes(StandardCharsets.UTF_8))).append('\n');
  }
  static byte[] canonical(JsonNode spec) throws Exception {
    StringBuilder out = new StringBuilder("relshub-release-v1\n");
    for (String key : List.of("project", "version", "buildNumber", "os", "arch")) field(out, spec.path(key));
    String notes = spec.path("notes").asText(); require(notes.length() <= 100000, "发布说明过长");
    field(out, Json.MAPPER.valueToTree(sha(notes.getBytes(StandardCharsets.UTF_8))));
    for (String key : List.of("sourceMin", "sourceMax", "intermediate", "schemaMin", "schemaMax", "targetSchema", "rollbackAllowed", "keyId")) field(out, spec.path(key));
    var rows = files(spec); field(out, Json.MAPPER.valueToTree(rows.size()));
    for (var row : rows) for (String key : List.of("name", "kind", "sourceVersion", "size", "sha256")) field(out, row.path(key));
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }
  public static JsonNode index(URI feed, JsonNode index, JsonNode keys) throws Exception {
    channel(feed); id(index); JsonNode spec = index.path("spec");
    require("syncsrv".equals(spec.path("project").asText()) && "linux".equals(spec.path("os").asText()) && "x64".equals(spec.path("arch").asText()), "发布项目或平台不匹配");
    require(spec.path("buildNumber").isTextual() && !spec.path("buildNumber").asText().isBlank() && spec.path("buildNumber").asText().length() <= 80
        && spec.path("notes").isTextual(), "发布构建号或说明格式无效");
    compare(spec.path("version").asText(), spec.path("version").asText());
    require(compare(spec.path("sourceMin").asText(), spec.path("sourceMax").asText()) <= 0, "来源版本范围无效");
    for (String key : List.of("schemaMin", "schemaMax", "targetSchema")) require(spec.path(key).isIntegralNumber() && spec.path(key).asInt() == 1, "此数据库架构需要人工处理");
    require(spec.path("rollbackAllowed").isBoolean() && spec.path("rollbackAllowed").asBoolean(), "发布未承诺回滚兼容");
    require("Ed25519".equals(index.path("algorithm").asText()), "不支持的发布签名算法");
    byte[] canonical = canonical(spec);
    if (!Arrays.equals(canonical, Base64.getDecoder().decode(index.path("canonicalBase64").asText()))) throw new SecurityException("发布清单规范字节不一致");
    ReleaseTrust.authenticate(canonical, index.path("signature").asText(), spec.path("keyId").asText(), keys);
    require(index.path("files").isArray() && index.path("files").size() == spec.path("files").size(), "制品清单不一致");
    var names = new HashSet<String>();
    for (var row : index.path("files")) {
      require(names.add(row.path("name").asText()), "制品重复"); artifact(feed, index, row);
      var signed = files(spec).stream().filter(f -> f.path("name").equals(row.path("name"))).findFirst().orElseThrow(() -> new IllegalArgumentException("未签名制品"));
      for (String key : List.of("name", "kind", "size", "sha256")) require(signed.path(key).equals(row.path(key)), "签名制品元数据不一致");
      require(Objects.equals(signed.path("sourceVersion").asText(""), row.path("sourceVersion").asText("")), "补丁来源不一致");
    }
    return spec;
  }
  public static JsonNode resolve(URI feed, JsonNode index, byte[] sidecar, JsonNode keys, Instant now) throws Exception {
    JsonNode spec = index(feed, index, keys), descriptor = file(index, "INSTALLER", SIDECAR);
    require(sidecar.length <= 131072 && sidecar.length == descriptor.path("size").asLong() && sha(sidecar).equals(descriptor.path("sha256").asText()), "原生清单大小或摘要不一致");
    JsonNode release = ReleaseTrust.verify(Json.MAPPER.readTree(sidecar), "SyncSrv", keys, now, channel(feed));
    compare(release.path("rollbackMinimumVersion").asText(), release.path("version").asText());
    require("linux-x64".equals(release.path("target").asText()) && "relshub-v1".equals(release.path("distribution").asText())
        && release.path("version").equals(spec.path("version")), "原生清单版本或平台不匹配");
    for (String key : List.of("sourceMin", "sourceMax", "schemaMin", "schemaMax")) require(release.path(key).equals(spec.path(key)), "签名兼容范围不一致");
    require(spec.path("targetSchema").asInt() == 1 && spec.path("rollbackAllowed").asBoolean() && spec.path("intermediate").asText("").isEmpty(), "此发布需要人工处理架构或中间版本");
    URI repository = URI.create(release.path("asset").path("repository").asText());
    require(sameOrigin(feed, repository) && repository.getRawUserInfo() == null && repository.getRawQuery() == null && repository.getRawFragment() == null
        && Set.of("", "/").contains(repository.getRawPath()), "签名仓库来源不匹配，请使用已签名的更新源或由发布人员签发新版本");
    JsonNode asset = release.path("asset"), full = file(index, "FULL", null);
    for (String key : List.of("name", "size", "sha256")) require(asset.path(key).equals(full.path(key)), "安装包签名摘要不匹配");
    require(asset.path("name").asText().equals("syncsrv-" + release.path("version").asText() + "-linux-x64.tar.gz")
        && asset.path("size").asLong() <= 1073741824L && release.path("applicationSha256").asText().matches("[a-f0-9]{64}"), "安装包身份无效");
    ObjectNode result = release.deepCopy(); ((ObjectNode)result.path("asset")).put("url", artifact(feed, index, full).toString());
    return result;
  }
  static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
  public static int compare(String a, String b) {
    var pattern = "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)";
    require(a.matches(pattern) && b.matches(pattern) && a.length() <= 32 && b.length() <= 32, "仅支持规范的三段版本号");
    var x = a.split("\\."); var y = b.split("\\.");
    for (int i = 0; i < 3; i++) { int n = new java.math.BigInteger(x[i]).compareTo(new java.math.BigInteger(y[i])); if (n != 0) return n; }
    return 0;
  }
  static String compatibility(JsonNode release, String installed) {
    if (compare(release.path("version").asText(), installed) < 0) return "发布版本较旧；降级必须使用显式回滚";
    if (compare(release.path("version").asText(), installed) == 0) return "";
    for (String k : List.of("sourceMin", "sourceMax", "rollbackMinimumVersion")) if (!release.path(k).isTextual()) return "发布缺少升级或回滚兼容范围";
    if (compare(installed, release.path("sourceMin").asText()) < 0 || compare(installed, release.path("sourceMax").asText()) > 0) return "当前版本不在签名升级范围内";
    if (compare(installed, release.path("rollbackMinimumVersion").asText()) < 0 || release.path("schemaMin").asInt() != 1 || release.path("schemaMax").asInt() != 1) return "数据库架构或回滚兼容性需要人工确认";
    return "";
  }
  public static void command(String[] args) throws Exception {
    if (args.length != 3 || Files.size(Path.of(args[1])) > 262144) throw new IllegalArgumentException("verify-relshub-index FILE FEED");
    var document = Json.MAPPER.readTree(Files.readAllBytes(Path.of(args[1])));
    index(URI.create(args[2]), document, ReleaseTrust.keys()); System.out.println(document);
  }
}
