package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.auth.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.*;
import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import com.oadig.syncsrv.shared.web.GlobalExceptionHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@EnabledIfSystemProperty(named = "syncsrv.agent.binary", matches = ".+")
class AgentPushE2ETest {
  @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS)
  Path root;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"LOCAL", "SFTP", "FTP", "WEBDAV", "SMB"})
  void nativeAgentEnrollsWatchesPushesAndRestores(String protocol) throws Exception {
    String binary = System.getProperty("syncsrv.agent.binary");
    Assumptions.assumeTrue(System.getProperty("syncsrv.protocol", protocol).equals(protocol));
    if (protocol.equals("SMB"))
      Assumptions.assumeTrue(System.getProperty("syncsrv.smb.python") != null);
    MemoryStore memory = new MemoryStore();
    ConfigurationService config = mock(ConfigurationService.class);
    Path source = Files.createDirectory(root.resolve("A"));
    Files.writeString(source.resolve("file.txt"), "first");
    byte[] large = new byte[1_200_000];
    new Random(7).nextBytes(large);
    Files.write(source.resolve("大文件 & space.bin"), large);
    Files.write(source.resolve("empty.txt"), new byte[0]);
    Path b = Files.createDirectory(root.resolve("B")), c = Files.createDirectory(root.resolve("C"));
    Job job = new Job();
    job.id = Ids.next();
    job.sourceMode = "AGENT";
    job.discoveryMode = "EVENTS";
    job.agentRoot = source.toString();
    job.targetId = "B";
    job.archiveId = "C";
    job.enabled = true;
    job.intervalSeconds = 2;
    when(config.job(job.id)).thenReturn(job);
    when(config.jobs()).thenReturn(List.of(job));
    for (String id : List.of("B", "C")) {
      Endpoint e = new Endpoint();
      e.id = id;
      e.root = root.resolve(id).toString();
      when(config.endpoint(id)).thenReturn(e);
    }
    Process smb = null;
    org.apache.sshd.server.SshServer ssh = null;
    org.apache.ftpserver.FtpServer ftp = null;
    WebDavTest webB = null, webD = null;
    Endpoint destination = new Endpoint();
    destination.id = "D";
    destination.root = Files.createDirectory(root.resolve("D")).toString();
    if (protocol.equals("SMB")) {
      int port;
      try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
        port = socket.getLocalPort();
      }
      Path fixture = Path.of(AgentPushE2ETest.class.getResource("/smb-fixture.py").toURI());
      smb =
          new ProcessBuilder(
                  System.getProperty("syncsrv.smb.python"),
                  fixture.toString(),
                  "--root",
                  root.toString(),
                  "--port",
                  Integer.toString(port))
              .redirectErrorStream(true)
              .redirectOutput(root.resolve("smb.log").toFile())
              .start();
      final int listeningPort = port;
      await(
          () -> {
            try (var socket = new java.net.Socket("127.0.0.1", listeningPort)) {
              return true;
            } catch (Exception e) {
              return false;
            }
          },
          root.resolve("smb.log"));
      for (String id : List.of("B", "D")) {
        Endpoint endpoint = new Endpoint();
        endpoint.id = id;
        endpoint.protocol = "SMB";
        endpoint.host = "127.0.0.1";
        endpoint.port = port;
        endpoint.root = "test/" + id;
        endpoint.username = "test";
        endpoint.password = "fixture-only";
        when(config.endpoint(id)).thenReturn(endpoint);
      }
    } else if (protocol.equals("SFTP")) {
      ssh = org.apache.sshd.server.SshServer.setUpDefaultServer();
      ssh.setHost("127.0.0.1");
      ssh.setPort(0);
      var keys =
          new org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider(
              root.resolve("ssh.key"));
      keys.setAlgorithm("RSA");
      ssh.setKeyPairProvider(keys);
      ssh.setFileSystemFactory(
          new org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(root));
      ssh.setPasswordAuthenticator((u, p, se) -> u.equals("test") && p.equals("fixture-only"));
      ssh.setSubsystemFactories(
          List.of(new org.apache.sshd.sftp.server.SftpSubsystemFactory.Builder().build()));
      ssh.start();
      for (String id : List.of("B", "D")) {
        Endpoint endpoint = new Endpoint();
        endpoint.id = id;
        endpoint.protocol = "SFTP";
        endpoint.host = "127.0.0.1";
        endpoint.port = ssh.getPort();
        endpoint.root = "/" + id;
        endpoint.username = "test";
        endpoint.password = "fixture-only";
        endpoint.hostKey =
            org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(
                keys.loadKeys(null).iterator().next().getPublic());
        when(config.endpoint(id)).thenReturn(endpoint);
      }
    } else if (protocol.equals("FTP")) {
      var factory = new org.apache.ftpserver.FtpServerFactory();
      var listener = new org.apache.ftpserver.listener.ListenerFactory();
      listener.setPort(0);
      listener.setServerAddress("127.0.0.1");
      factory.addListener("default", listener.createListener());
      var user = new org.apache.ftpserver.usermanager.impl.BaseUser();
      user.setName("test");
      user.setPassword("fixture-only");
      user.setHomeDirectory(root.toString());
      user.setAuthorities(List.of(new org.apache.ftpserver.usermanager.impl.WritePermission()));
      factory.getUserManager().save(user);
      ftp = factory.createServer();
      ftp.start();
      for (String id : List.of("B", "D")) {
        Endpoint endpoint = new Endpoint();
        endpoint.id = id;
        endpoint.protocol = "FTP";
        endpoint.host = "127.0.0.1";
        endpoint.port = factory.getListener("default").getPort();
        endpoint.root = "/" + id;
        endpoint.username = "test";
        endpoint.password = "fixture-only";
        when(config.endpoint(id)).thenReturn(endpoint);
      }
    } else if (protocol.equals("WEBDAV")) {
      webB = new WebDavTest();
      webB.root = b;
      webB.start();
      webB.endpoint.id = "B";
      URI origin = URI.create(webB.endpoint.host);
      webB.endpoint.port = origin.getPort();
      webB.endpoint.host = "http://127.0.0.1";
      webD = new WebDavTest();
      webD.root = root.resolve("D");
      webD.start();
      webD.endpoint.id = "D";
      when(config.endpoint("B")).thenReturn(webB.endpoint);
      when(config.endpoint("D")).thenReturn(webD.endpoint);
      var directory = com.oadig.syncsrv.platform.persistence.Store.class.getField("directory");
      directory.setAccessible(true);
      directory.set(memory.store, Files.createDirectory(root.resolve("center-data")));
    } else when(config.endpoint("D")).thenReturn(destination);
    TransferService transfer = new TransferService(memory.store);
    SyncEngine sync = new SyncEngine(memory.store, config, transfer);
    RestoreService restore = new RestoreService(memory.store, config, sync, transfer);
    AgentService agents = new AgentService(memory.store, config);
    try (TaskManager tasks = new TaskManager(memory.store, config, sync, restore)) {
      var api =
          MockMvcBuilders.standaloneSetup(
                  new AgentController(agents, config, memory.store, sync, tasks),
                  new AgentPushController(agents, memory.store, config),
                  new SourceChangesController(agents, memory.store, config))
              .setControllerAdvice(new GlobalExceptionHandler())
              .build();
      HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      ExecutorService executor = Executors.newCachedThreadPool();
      http.setExecutor(executor);
      var binaryRequests = new java.util.concurrent.atomic.AtomicInteger();
      http.createContext(
          "/",
          exchange -> {
            try {
              if (exchange.getRequestURI().getPath().contains("/chunk/"))
                binaryRequests.incrementAndGet();
              var request =
                  MockMvcRequestBuilders.request(
                          org.springframework.http.HttpMethod.valueOf(exchange.getRequestMethod()),
                          exchange.getRequestURI().toString())
                      .content(exchange.getRequestBody().readAllBytes());
              exchange
                  .getRequestHeaders()
                  .forEach((name, values) -> request.header(name, values.toArray()));
              var response = api.perform(request).andReturn().getResponse();
              byte[] body = response.getContentAsByteArray();
              response
                  .getHeaderNames()
                  .forEach(
                      name ->
                          exchange
                              .getResponseHeaders()
                              .put(name, new ArrayList<>(response.getHeaders(name))));
              exchange.sendResponseHeaders(response.getStatus(), body.length);
              exchange.getResponseBody().write(body);
            } catch (Exception e) {
              exchange.sendResponseHeaders(500, -1);
            } finally {
              exchange.close();
            }
          });
      http.start();
      Process running = null;
      try {
        String server = "http://127.0.0.1:" + http.getAddress().getPort();
        var invitation = agents.invitation("test");
        Path token = root.resolve("token");
        Files.writeString(token, invitation.token());
        Path data = root.resolve("agent-data");
        Path log = root.resolve("agent.log");
        Process enrollment =
            new ProcessBuilder(
                    binary,
                    "enroll",
                    "--server",
                    server,
                    "--data-dir",
                    data.toString(),
                    "--token-file",
                    token.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        assertTrue(enrollment.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, enrollment.exitValue(), () -> read(log));
        job.agentId = agents.devices().get(0).id;
        agents.authorize(job.agentId, true, "test");
        tasks.start();
        running =
            new ProcessBuilder(binary, "run", "--data-dir", data.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
        await(() -> memory.objects.getOrDefault("source-change", Map.of()).values().stream()
            .anyMatch(value -> value.contains("WAITING")), log);
        assertFalse(Files.exists(b.resolve("file.txt")), "waiting change must be visible before payload transfer");
        await(() -> read(b.resolve("file.txt")).equals("first"), log);
        await(() -> !tasks.busy(), log);
        Snapshot first = sync.snapshots().get(0);
        assertArrayEquals(large, Files.readAllBytes(b.resolve("大文件 & space.bin")));
        assertEquals(0, Files.size(b.resolve("empty.txt")));
        Files.writeString(source.resolve("file.txt"), "second");
        await(() -> read(b.resolve("file.txt")).equals("second"), log);
        await(() -> !tasks.busy(), log);
        Files.createDirectories(source.resolve("new/nested"));
        Files.writeString(source.resolve("new/nested/child"), "child");
        await(() -> read(b.resolve("new/nested/child")).equals("child"), log);
        await(() -> !tasks.busy(), log);
        Files.delete(source.resolve("file.txt"));
        await(() -> !Files.exists(b.resolve("file.txt")), log);
        await(() -> !tasks.busy(), log);
        Run run = new Run();
        run.id = Ids.next();
        run.jobId = job.id;
        run.actor = "test";
        restore.restore(
            first.id(), "D", "snapshot-one", new TaskContext(run, new BandwidthLimiter(0), 0));
        assertEquals("first", Files.readString(root.resolve("D/snapshot-one/file.txt")));
        assertArrayEquals(
            large, Files.readAllBytes(root.resolve("D/snapshot-one/大文件 & space.bin")));
        assertEquals(0, Files.size(root.resolve("D/snapshot-one/empty.txt")));
        assertTrue(memory.actions.contains("source.changes_notified"));
        assertTrue(memory.actions.contains("source.changes_completed"));
        assertTrue(memory.actions.indexOf("source.changes_notified") < memory.actions.indexOf("source.upload_started"));
        assertTrue(memory.actions.contains("source.direct_verified"));
        assertTrue(memory.actions.contains("source.direct_staged"));
        assertEquals(0, binaryRequests.get(), "Source payloads must not reach the center HTTP API");
        assertTrue(memory.actions.contains("snapshot.completed"));
        System.out.println(
            "DIRECT_AGENT_E2E_OK ["
                + protocol
                + "]: no payload relay, enroll, native change, nested file,"
                + " deletion, historical restore");
      } finally {
        if (running != null) {
          running.destroy();
          if (!running.waitFor(10, TimeUnit.SECONDS)) running.destroyForcibly();
        }
        http.stop(0);
        executor.shutdownNow();
      }
    } finally {
      if (smb != null) {
        smb.destroy();
        if (!smb.waitFor(5, TimeUnit.SECONDS)) smb.destroyForcibly();
      }
      if (ssh != null) ssh.stop(true);
      if (ftp != null) ftp.stop();
      if (webB != null) webB.stop();
      if (webD != null) webD.stop();
    }
  }

  private static String read(Path p) {
    try {
      return Files.readString(p);
    } catch (Exception e) {
      return "";
    }
  }

  private static void await(BooleanSupplier condition, Path log) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) fail("Timed out. Agent log: " + read(log));
      Thread.sleep(100);
    }
  }
}
