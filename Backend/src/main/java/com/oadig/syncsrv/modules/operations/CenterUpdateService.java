package com.oadig.syncsrv.modules.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.oadig.syncsrv.shared.BuildIdentity;
import com.oadig.syncsrv.shared.Json;
import com.oadig.syncsrv.platform.persistence.Store;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Metadata-only checks plus audited source preferences; never installs or executes commands. */
@Service
public class CenterUpdateService {
  static final String DEFAULT_FEED = "https://update.devgo.io:18761/api/v1/projects/syncsrv/channels/stable/manifest?os=linux&arch=x64";
  public record Result(String status, String message, String currentVersion, String latestVersion,
      String project, String channel, String os, String arch, String releaseId, String signature,
      String sourceMin, String sourceMax, String checkedAt, boolean platformSupported, String manifestUrl) {}
  interface Fetcher { byte[] get(URI uri, int maximum) throws Exception; }
  private final Environment environment;
  private final Fetcher fetcher;
  private final JsonNode keys;
  private final String installed;
  private final boolean supported;
  private final ReentrantLock checking = new ReentrantLock();
  private final UpdateSourceConfiguration configuration;

  @org.springframework.beans.factory.annotation.Autowired
  public CenterUpdateService(Environment environment, Store store) throws Exception {
    this(environment, CenterUpdateService::fetch, ReleaseTrust.keys(), BuildIdentity.version(),
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")
        && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch", "")),
        auditedConfiguration(environment, store));
    try { configuration.current(); }
    catch (Exception unavailable) { /* Optional update failures are reported by source/check; backups can still start. */ }
  }
  private static UpdateSourceConfiguration auditedConfiguration(Environment environment, Store store) {
    return new UpdateSourceConfiguration(environment, store.directory, new UpdateSourceConfiguration.Audit() {
      public void record(String actor, String action, Map<String,Object> detail) throws Exception {
        if (!detail.containsKey("state")) { store.commit(actor, action, "updates.source", detail); return; }
        JsonNode state = (JsonNode) detail.get("state");
        var change = state.isNull() ? Store.Change.remove("settings", "update-source") : Store.Change.put("settings", "update-source", state);
        store.commit(actor, action, "updates.source", detail, change);
      }
      private JsonNode committed() throws Exception { return store.get("settings", "update-source", JsonNode.class); }
      public JsonNode recoverable() throws Exception { return committed(); }
      public void verify(JsonNode state) throws Exception {
        if (!Objects.equals(committed(), state)) throw new IllegalStateException("更新源文件与签名审计不一致，已停止检查");
      }
      public void verifyPending(JsonNode pending, JsonNode previous) throws Exception {
        JsonNode recorded = committed();
        var next = ((com.fasterxml.jackson.databind.node.ObjectNode) pending).deepCopy().put("status", "COMMITTED"); next.remove("previous");
        if (!Objects.equals(recorded, previous == null || previous.isNull() ? null : previous) && !Objects.equals(recorded, next))
          throw new IllegalStateException("未提交的更新源与签名审计不一致，已停止恢复");
      }
    });
  }
  CenterUpdateService(Environment environment, Fetcher fetcher, JsonNode keys, String installed, boolean supported) {
    this(environment, fetcher, keys, installed, supported, new UpdateSourceConfiguration(environment,
        Path.of(environment.getProperty("syncsrv.data", "./data")), (actor, action, detail) -> {}));
  }
  CenterUpdateService(Environment environment, Fetcher fetcher, JsonNode keys, String installed, boolean supported, UpdateSourceConfiguration configuration) {
    this.environment = environment; this.fetcher = fetcher; this.keys = keys; this.installed = installed; this.supported = supported;
    this.configuration = configuration;
  }
  private UpdateSourceConfiguration.Source source() throws Exception {
    return configuration.current();
  }
  static String serverOrigin(String value){
    try{
      if(value==null||value.length()>2048||!value.matches("https://[^/?#\\s]+/?"))throw new IllegalArgumentException();
      URI uri=URI.create(value);if(uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getPort()==0||uri.getPort()>65535)throw new IllegalArgumentException();
      return uri.getScheme()+"://"+uri.getRawAuthority();
    }catch(Exception bad){throw new IllegalArgumentException("只填写 HTTPS 服务器根地址，不含路径、查询参数或凭据");}
  }
  static String manifestForServer(String value,String prior){
    String channel=ReleaseContract.channel(URI.create(prior));
    return serverOrigin(value)+"/api/v1/projects/syncsrv/channels/"+channel+"/manifest?os=linux&arch=x64";
  }
  public Map<String,String> sourceConfiguration() throws Exception { return configuration.configuration(); }
  public Map<String,String> saveServer(String value, String expected, String actor) throws Exception {
    if (!checking.tryLock()) throw new IllegalStateException("正在检查更新，请稍后保存");
    try { return configuration.save(value, expected, actor); }
    finally { checking.unlock(); }
  }
  static boolean privateLiteral(String value) {
    if (value == null) return false;
    String host = value.replace("[", "").replace("]", "");
    try {
      if (host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
        for (String part : host.split("\\.")) if (Integer.parseInt(part) > 255 || part.length() > 1 && part.startsWith("0")) return false;
        byte[] bytes = InetAddress.getByName(host).getAddress(); int a = bytes[0] & 255, b = bytes[1] & 255;
        return a == 10 || a == 127 || a == 192 && b == 168 || a == 172 && b >= 16 && b <= 31;
      }
      if (host.contains(":") && host.matches("[0-9a-fA-F:]+")) {
        byte[] bytes = InetAddress.getByName(host).getAddress();
        return bytes.length == 16 && ((bytes[0] & 254) == 252 || InetAddress.getByAddress(bytes).isLoopbackAddress());
      }
    } catch (java.net.UnknownHostException ignored) { }
    return false;
  }
  private Result result(String status, String message, UpdateSourceConfiguration.Source source, JsonNode index, JsonNode release) {
    return new Result(status, message, installed, release == null ? "" : release.path("version").asText(), "syncsrv",
        source == null ? "" : source.channel(), "linux", "x64", index == null ? "" : index.path("releaseId").asText(),
        release == null ? "未验证" : "清单与原生签名已验证", release == null ? "" : release.path("sourceMin").asText(),
        release == null ? "" : release.path("sourceMax").asText(), Instant.now().toString(), supported,
        source == null ? "" : source.feed().toString());
  }
  public Result check() {
    if (!checking.tryLock()) return result("CHECKING", "已有版本检查正在进行，请稍后重试", null, null, null);
    UpdateSourceConfiguration.Source source = null;
    try {
      source = source();
      JsonNode index = Json.MAPPER.readTree(fetcher.get(source.feed(), 262144));
      ReleaseContract.index(source.feed(), index, keys);
      URI sidecar = ReleaseContract.artifact(source.feed(), index, ReleaseContract.file(index, "INSTALLER", ReleaseContract.SIDECAR));
      JsonNode release = ReleaseContract.resolve(source.feed(), index, fetcher.get(sidecar, 131072), keys, Instant.now());
      String reason = ReleaseContract.compatibility(release, installed);
      if (!reason.isEmpty()) return result("INCOMPATIBLE", reason, source, index, release);
      if (ReleaseContract.compare(release.path("version").asText(), installed) == 0) return result("UP_TO_DATE", "当前版本已是渠道推荐版本", source, index, release);
      return result("UPDATE_AVAILABLE", supported ? "有兼容新版本；请由管理员在服务器手动运行升级脚本" : "有兼容的 Linux x64 发行版；当前平台仅供核验，不能在本机安装", source, index, release);
    } catch (SecurityException | java.security.GeneralSecurityException error) {
      return result("SIGNATURE_REJECTED", "发布签名验证失败，请核对发布公钥和文件完整性", source, null, null);
    } catch (IllegalArgumentException error) {
      String reason = error instanceof ReleaseContract.InvalidContract ? error.getMessage() : "更新源、通道、制品路径或签名兼容元数据不符合发布合同";
      return result("CONTRACT_REJECTED", reason, source, null, null);
    } catch (Exception error) {
      String reason = "版本检查失败，请检查更新源连接、证书、权限及清单格式后重试";
      for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
        if (cause instanceof FetchFailure) { reason = cause.getMessage(); break; }
        if (cause instanceof javax.net.ssl.SSLException) { reason = "更新源 TLS 验证失败，请检查证书链、域名和有效期"; break; }
        if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) { reason = "更新源连接或读取超时，请稍后重试"; break; }
      }
      return result("CHECK_FAILED", reason, source, null, null);
    } finally { checking.unlock(); }
  }
  private static byte[] fetch(URI uri, int maximum) throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(6)).header("User-Agent", "SyncSrv-VersionCheck").GET().build();
    var future = client.sendAsync(request, info -> new LimitedBody(maximum));
    try {
      var response = future.get(7, TimeUnit.SECONDS);
      int status = response.statusCode();
      if (status != 200) throw new FetchFailure(status == 401 || status == 403 ? "更新源拒绝访问，请确认项目和通道的读取权限"
          : status == 404 ? "更新源未找到该项目和平台的发布清单"
          : status >= 300 && status < 400 ? "更新源要求跳转，已拒绝跟随重定向" : "更新源返回 HTTP " + status + "，请稍后重试");
      return response.body();
    } finally { if (!future.isDone()) future.cancel(true); }
  }
  static final class FetchFailure extends java.io.IOException { FetchFailure(String message) { super(message); } }
  static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximum;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;
    LimitedBody(int maximum) { this.maximum = maximum; }
    @Override public CompletionStage<byte[]> getBody() { return body; }
    @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
    @Override public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > maximum - bytes.size()) { subscription.cancel(); body.completeExceptionally(new java.io.IOException("更新清单过大")); return; }
        byte[] block = new byte[buffer.remaining()]; buffer.get(block); bytes.writeBytes(block);
      }
      subscription.request(1);
    }
    @Override public void onError(Throwable error) { body.completeExceptionally(error); }
    @Override public void onComplete() { body.complete(bytes.toByteArray()); }
  }
}
