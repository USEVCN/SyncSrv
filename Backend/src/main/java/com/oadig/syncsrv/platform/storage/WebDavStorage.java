package com.oadig.syncsrv.platform.storage;

import com.oadig.syncsrv.shared.Models.Endpoint;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public final class WebDavStorage implements Storage {
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(15))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final Duration idleTimeout;
  private final Set<WebDavExchange> exchanges = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final URI base;
  private final String authorization;

  public WebDavStorage(Endpoint e) throws Exception { this(e, Duration.ofSeconds(30)); }

  WebDavStorage(Endpoint e, Duration idleTimeout) throws Exception {
    this.idleTimeout = idleTimeout;
    if (idleTimeout.isNegative() || idleTimeout.isZero()) throw new IllegalArgumentException("WebDAV timeout must be positive");
    URI origin = URI.create(e.host);
    if (!List.of("http", "https").contains(origin.getScheme())
        || origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null)
      throw new IllegalArgumentException("WebDAV 地址须为 http/https，凭据请单独填写");
    String root = origin.getPath().replaceAll("/+$", "") + "/" + e.root.replaceAll("^/+|/+$", "");
    base =
        new URI(
            origin.getScheme(),
            null,
            origin.getHost(),
            e.port > 0 ? e.port : origin.getPort(),
            root.replaceAll("/+$", "") + "/",
            null,
            null);
    authorization =
        e.username.isEmpty()
            ? ""
            : "Basic "
                + Base64.getEncoder()
                    .encodeToString(
                        (e.username + ":" + e.password).getBytes(StandardCharsets.UTF_8));
  }

  private URI path(String p) throws Exception {
    StoragePaths.relative(p);
    return new URI(
        base.getScheme(), null, base.getHost(), base.getPort(), base.getPath() + p, null, null);
  }

  private HttpRequest.Builder request(String p) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(path(p));
    if (!authorization.isEmpty()) b.header("Authorization", authorization);
    return b;
  }

  private WebDavExchange exchange() throws IOException {
    if (closed.get()) throw new IOException("WebDAV 连接已关闭");
    WebDavExchange exchange = new WebDavExchange(client, idleTimeout, exchanges::remove);
    exchanges.add(exchange);
    if (closed.get()) { exchange.close(); throw new IOException("WebDAV 连接已关闭"); }
    return exchange;
  }

  private HttpResponse<InputStream> send(HttpRequest request) throws Exception {
    WebDavExchange exchange = exchange();
    try { return exchange.send(request); }
    catch (Exception e) { exchange.close(); throw e; }
  }

  private void status(HttpResponse<?> r, int... valid) throws IOException {
    for (int c : valid) if (r.statusCode() == c) return;
    throw new IOException("WebDAV 请求失败，HTTP " + r.statusCode());
  }

  private static String text(Element e, String name) {
    NodeList nodes = e.getElementsByTagNameNS("DAV:", name);
    return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
  }

  private List<Map.Entry<String, Item>> properties(String p, String depth) throws Exception {
    String xml =
        "<?xml version=\"1.0\"?><d:propfind"
            + " xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>";
    HttpResponse<InputStream> res =
        send(
            request(p)
                .header("Depth", depth)
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(xml))
                .build());
    try (InputStream body = res.body()) {
      if (res.statusCode() == 404 && depth.equals("0")) return List.of();
      status(res, 207);
      byte[] data = body.readNBytes(32 * 1024 * 1024 + 1);
      if (data.length > 32 * 1024 * 1024) throw new IOException("WebDAV 单目录响应超过32MiB");
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
      NodeList nodes = doc.getElementsByTagNameNS("DAV:", "response");
      List<Map.Entry<String, Item>> out = new ArrayList<>();
      for (int i = 0; i < nodes.getLength(); i++) {
        Element response = (Element) nodes.item(i);
        String href = text(response, "href");
        URI uri = base.resolve(href);
        if (!Objects.equals(uri.getHost(), base.getHost())
            || !Objects.equals(uri.getScheme(), base.getScheme())
            || uri.getPort() != base.getPort()) throw new IOException("WebDAV 返回跨站资源");
        String resolved = uri.getPath().replaceAll("/+$", "");
        String root = base.getPath().replaceAll("/+$", "");
        if (!resolved.equals(root) && !resolved.startsWith(root + "/"))
          throw new IOException("WebDAV 返回越界路径");
        String rel = resolved.equals(root) ? "" : resolved.substring(root.length() + 1);
        StoragePaths.relative(rel);
        NodeList groups = response.getElementsByTagNameNS("DAV:", "propstat");
        Element good = null;
        for (int j = 0; j < groups.getLength(); j++) {
          Element g = (Element) groups.item(j);
          if (text(g, "status").contains(" 200 ")) {
            good = g;
            break;
          }
        }
        if (good == null) throw new IOException("WebDAV 条目属性不可访问");
        boolean dir = good.getElementsByTagNameNS("DAV:", "collection").getLength() > 0;
        String length = text(good, "getcontentlength");
        long size = dir ? 0 : Long.parseLong(length);
        long modified = 0;
        try {
          modified =
              ZonedDateTime.parse(
                      text(good, "getlastmodified"), DateTimeFormatter.RFC_1123_DATE_TIME)
                  .toInstant()
                  .toEpochMilli();
        } catch (Exception ignored) {
        }
        out.add(Map.entry(rel, new Item(StoragePaths.name(rel), dir, size, modified, false)));
      }
      return out;
    }
  }

  @Override
  public Item stat(String p) throws Exception {
    for (var e : properties(p, "0")) if (e.getKey().equals(p)) return e.getValue();
    return null;
  }

  @Override
  public List<Item> list(String p) throws Exception {
    List<Item> out = new ArrayList<>();
    boolean self = false;
    for (var e : properties(p, "1")) {
      if (e.getKey().equals(p)) {
        self = true;
        continue;
      }
      if (!StoragePaths.parent(e.getKey()).equals(p)) throw new IOException("WebDAV 返回非直接子路径");
      out.add(e.getValue());
    }
    if (!self) throw new IOException("WebDAV 缺少目录自身，拒绝把不完整结果视为空目录");
    return out;
  }

  @Override
  public InputStream read(String p, long offset) throws Exception {
    HttpRequest.Builder b = request(p);
    if (offset > 0) b.header("Range", "bytes=" + offset + "-");
    HttpResponse<InputStream> res = send(b.GET().build());
    try {
      status(res, offset > 0 ? 206 : 200);
      if (offset > 0
          && !res.headers()
              .firstValue("Content-Range")
              .orElse("")
              .startsWith("bytes " + offset + "-")) throw new IOException("WebDAV 未按偏移返回内容");
      return res.body();
    } catch (Exception e) {
      res.body().close();
      throw e;
    }
  }

  @Override
  public void write(String p, long offset, InputStream data, long length) throws Exception {
    if (offset != 0) throw new IOException("普通 WebDAV 不支持通用上传断点，请整文件重试");
    mkdirs(StoragePaths.parent(p));
    try (WebDavExchange exchange = exchange()) {
      HttpRequest.BodyPublisher body = exchange.upload(data, length);
      HttpResponse<InputStream> res = exchange.send(request(p).PUT(body).build());
      try (InputStream ignored = res.body()) { status(res, 200, 201, 204); }
    }
  }

  @Override
  public void mkdirs(String p) throws Exception {
    if (p.isEmpty()) return;
    String current = "";
    for (String part : p.split("/")) {
      current = current.isEmpty() ? part : current + "/" + part;
      Item i = stat(current);
      if (i != null) {
        if (!i.directory()) throw new IOException("WebDAV 目录冲突");
        continue;
      }
      HttpResponse<InputStream> r =
          send(request(current).method("MKCOL", HttpRequest.BodyPublishers.noBody()).build());
      try (InputStream ignored = r.body()) {
        status(r, 201);
      }
    }
  }

  @Override
  public void move(String a, String b) throws Exception {
    HttpResponse<InputStream> r =
        send(
            request(a)
                .header("Destination", path(b).toASCIIString())
                .header("Overwrite", "F")
                .method("MOVE", HttpRequest.BodyPublishers.noBody())
                .build());
    try (InputStream ignored = r.body()) {
      status(r, 201, 204);
    }
  }

  @Override
  public void delete(String p, boolean directory) throws Exception {
    if (directory && !list(p).isEmpty()) throw new IOException("禁止递归删除非空 WebDAV 目录");
    HttpResponse<InputStream> r = send(request(p).DELETE().build());
    try (InputStream ignored = r.body()) {
      status(r, 200, 204);
    }
  }

  @Override
  public Capabilities capabilities() {
    return new Capabilities(true, false, false, "下载依赖 HTTP Range；普通 PUT 上传中断后整文件重试");
  }

  @Override
  public void close() {
    closed.set(true);
    for (WebDavExchange exchange : exchanges) exchange.close();
  }
}
