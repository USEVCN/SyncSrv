package com.oadig.syncsrv;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.*;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import com.oadig.syncsrv.shared.Models.Endpoint;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class WebDavTest {
  @TempDir Path root;
  private HttpServer server;
  Endpoint endpoint;
  private java.util.concurrent.ExecutorService executor;
  private boolean ignoreRange;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = java.util.concurrent.Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
    endpoint = new Endpoint();
    endpoint.protocol = "WEBDAV";
    endpoint.host = "http://127.0.0.1:" + server.getAddress().getPort();
    endpoint.root = "/";
    endpoint.username = "test";
    endpoint.password = "fixture-only";
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
    if (executor != null) executor.shutdownNow();
  }

  @Test
  void agentUploadUsesDurableLocalStagingForNonResumableTarget() throws Exception {
    MemoryStore memory = new MemoryStore();
    Path data = Files.createTempDirectory("syncsrv-webdav-agent-");
    try {
      var directory = Store.class.getField("directory");
      directory.setAccessible(true);
      directory.set(memory.store, data);
      ConfigurationService config = mock(ConfigurationService.class);
      Job job = new Job();
      job.id = "webdav-job";
      job.agentId = "device";
      job.sourceMode = "AGENT";
      job.targetId = "B";
      job.archiveId = "C";
      job.enabled = true;
      when(config.job(job.id)).thenReturn(job);
      when(config.endpoint("B")).thenReturn(endpoint);
      Endpoint archive = new Endpoint();
      archive.root = Files.createDirectory(data.resolve("C")).toString();
      when(config.endpoint("C")).thenReturn(archive);
      AgentService.Device device = new AgentService.Device();
      device.id = "device";
      device.status = "APPROVED";
      memory.store.commit("test", "test", "device", Map.of(), put("agent", device.id, device));
      byte[] content = new byte[1200000];
      new Random(7).nextBytes(content);
      Node node = new Node("large.bin", false, content.length, 1, Ids.hash(content), "");
      AgentPushService push = new AgentPushService(memory.store, config);
      var session =
          push.begin("device", new AgentPushService.Begin(job.id, Ids.next(), List.of(""), 1));
      push.page("device", new AgentPushService.Page(session.id, 0, List.of(node)));
      push.prepare("device", new AgentPushService.FileRequest(job.id, node));
      push.chunk("device", job.id, node.hash(), 0, Arrays.copyOfRange(content, 0, 524288));
      push = new AgentPushService(memory.store, config);
      assertEquals(
          524288, push.prepare("device", new AgentPushService.FileRequest(job.id, node)).offset());
      for (int offset = 524288; offset < content.length; offset += 524288)
        push.chunk(
            "device",
            job.id,
            node.hash(),
            offset,
            Arrays.copyOfRange(content, offset, Math.min(content.length, offset + 524288)));
      push.finish("device", session.id);
      assertFalse(Files.exists(root.resolve("large.bin")));
      Run run = new Run();
      run.id = Ids.next();
      run.jobId = job.id;
      run.actor = "test";
      new SyncEngine(memory.store, config, new TransferService(memory.store))
          .sync(job, new TaskContext(run, new BandwidthLimiter(0), 0));
      assertArrayEquals(content, Files.readAllBytes(root.resolve("large.bin")));
      Files.createDirectory(root.resolve("D"));
      Endpoint destination = Json.read(Json.write(endpoint), Endpoint.class);
      destination.id = "D";
      destination.root = "/D";
      when(config.endpoint("D")).thenReturn(destination);
      var sync = new SyncEngine(memory.store, config, new TransferService(memory.store));
      new com.oadig.syncsrv.modules.restore.RestoreService(
              memory.store, config, sync, new TransferService(memory.store))
          .restore(
              sync.snapshots().get(0).id(),
              "D",
              "point-in-time",
              new TaskContext(run, new BandwidthLimiter(0), 0));
      assertArrayEquals(content, Files.readAllBytes(root.resolve("D/point-in-time/large.bin")));
      assertFalse(
          Files.exists(
              data.resolve("agent-upload-staging").resolve(AgentSource.blob(job.id, node.hash()))));
    } finally {
      try (var files = Files.walk(data)) {
        for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  @Test
  void webdavTreeUploadDownloadArchiveAndEmptyFile() throws Exception {
    try (Storage s = StorageFactory.open(endpoint)) {
      assertTrue(s.stat("").directory());
      assertNull(s.stat("missing"));
      s.mkdirs("中文/empty");
      s.write("中文/file & space.txt", 0, new ByteArrayInputStream("contents".getBytes()), 8);
      s.write("empty", 0, new ByteArrayInputStream(new byte[0]), 0);
      assertEquals(0, s.stat("empty").size());
      try (InputStream in = s.read("中文/file & space.txt", 3)) {
        assertEquals("tents", new String(in.readAllBytes()));
      }
      assertEquals(2, s.list("中文").size());
      s.move("中文/file & space.txt", "中文/history.v1");
      assertNull(s.stat("中文/file & space.txt"));
      assertThrows(Exception.class, () -> s.delete("中文", true));
      s.delete("中文/history.v1", false);
      s.delete("中文/empty", true);
      s.delete("中文", true);
      assertThrows(
          Exception.class, () -> s.write("empty", 1, new ByteArrayInputStream(new byte[] {1}), 1));
      assertFalse(s.capabilities().offsetWrite());
    }
  }

  @Test
  void ignoredRangeAndFailedAuthAreNotAccepted() throws Exception {
    Files.writeString(root.resolve("x"), "original");
    ignoreRange = true;
    try (Storage s = StorageFactory.open(endpoint)) {
      assertThrows(Exception.class, () -> s.read("x", 3));
    }
    endpoint.password = "incorrect";
    try (Storage s = StorageFactory.open(endpoint)) {
      assertThrows(Exception.class, () -> s.list(""));
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      String authorization =
          "Basic " + Base64.getEncoder().encodeToString("test:fixture-only".getBytes());
      if (!authorization.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"SyncSrv test\"");
        send(exchange, 401, new byte[0]);
        return;
      }
      String relative = exchange.getRequestURI().getPath().replaceAll("^/+|/+$", "");
      Path path = root.resolve(relative).normalize();
      if (!path.startsWith(root)) {
        send(exchange, 403, new byte[0]);
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "PROPFIND" -> {
          if (!Files.exists(path)) {
            send(exchange, 404, new byte[0]);
            return;
          }
          List<Path> entries = new ArrayList<>();
          entries.add(path);
          if ("1".equals(exchange.getRequestHeaders().getFirst("Depth")) && Files.isDirectory(path))
            try (var children = Files.list(path)) {
              entries.addAll(children.toList());
            }
          StringBuilder xml = new StringBuilder("<d:multistatus xmlns:d=\"DAV:\">");
          for (Path p : entries) {
            boolean directory = Files.isDirectory(p);
            String rel = root.relativize(p).toString().replace('\\', '/');
            String href =
                new URI(null, null, "/" + rel + (directory && !rel.isEmpty() ? "/" : ""), null)
                    .toASCIIString();
            xml.append("<d:response><d:href>")
                .append(escape(href))
                .append("</d:href><d:propstat><d:prop><d:resourcetype>")
                .append(directory ? "<d:collection/>" : "")
                .append("</d:resourcetype><d:getcontentlength>")
                .append(directory ? 0 : Files.size(p))
                .append("</d:getcontentlength><d:getlastmodified>")
                .append(
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis())
                            .atZone(ZoneOffset.UTC)))
                .append(
                    "</d:getlastmodified></d:prop><d:status>HTTP/1.1 200"
                        + " OK</d:status></d:propstat></d:response>");
          }
          xml.append("</d:multistatus>");
          send(exchange, 207, xml.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        case "MKCOL" -> {
          if (Files.exists(path)) send(exchange, 405, new byte[0]);
          else if (!Files.isDirectory(path.getParent())) send(exchange, 409, new byte[0]);
          else {
            Files.createDirectory(path);
            send(exchange, 201, new byte[0]);
          }
        }
        case "PUT" -> {
          try (InputStream body = exchange.getRequestBody()) {
            Files.copy(body, path, StandardCopyOption.REPLACE_EXISTING);
          }
          send(exchange, 201, new byte[0]);
        }
        case "GET" -> {
          byte[] bytes = Files.readAllBytes(path);
          String range = exchange.getRequestHeaders().getFirst("Range");
          if (range != null && !ignoreRange) {
            int offset = Integer.parseInt(range.substring(6, range.length() - 1));
            exchange
                .getResponseHeaders()
                .add(
                    "Content-Range",
                    "bytes " + offset + "-" + (bytes.length - 1) + "/" + bytes.length);
            send(exchange, 206, Arrays.copyOfRange(bytes, offset, bytes.length));
          } else send(exchange, 200, bytes);
        }
        case "MOVE" -> {
          String destination =
              URI.create(exchange.getRequestHeaders().getFirst("Destination")).getPath();
          Files.move(path, root.resolve(destination.substring(1)));
          send(exchange, 201, new byte[0]);
        }
        case "DELETE" -> {
          Files.delete(path);
          send(exchange, 204, new byte[0]);
        }
        default -> send(exchange, 405, new byte[0]);
      }
    } catch (Exception e) {
      send(exchange, 500, new byte[0]);
    } finally {
      exchange.close();
    }
  }

  private String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;");
  }

  private void send(HttpExchange e, int code, byte[] body) throws IOException {
    e.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
    if (body.length > 0) e.getResponseBody().write(body);
  }
}
