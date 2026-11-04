package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;

import com.oadig.syncsrv.modules.discovery.AgentDistributionController;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class AgentDistributionTest {
  @TempDir Path root;

  @Test
  void releaseAllowlistAndRealPathPreventUnrelatedFileDisclosure() throws Exception {
    Path releases = Files.createDirectory(root.resolve("releases"));
    Path version = Files.createDirectory(releases.resolve("0.3.0-dev"));
    Path binary = Files.writeString(version.resolve("syncsrv-agent-linux-amd64"), "binary");
    var controller = new AgentDistributionController(releases.toString());
    assertEquals(
        6,
        controller
            .release("0.3.0-dev", binary.getFileName().toString())
            .getHeaders()
            .getContentLength());
    assertThrows(ResponseStatusException.class, () -> controller.release("..", "secret"));
    assertThrows(
        ResponseStatusException.class, () -> controller.release("0.3.0-dev", "identity.json"));
    Path outside = Files.createDirectory(root.resolve("outside"));
    Files.writeString(outside.resolve("syncsrv-agent-linux-amd64"), "private");
    Files.createSymbolicLink(releases.resolve("escape"), outside);
    assertThrows(
        ResponseStatusException.class,
        () -> controller.release("escape", "syncsrv-agent-linux-amd64"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            new AgentDistributionController("").release("0.3.0-dev", "syncsrv-agent-linux-amd64"));
  }

  @Test
  void latestReleaseRequiresCompleteMatchingPackages() throws Exception {
    Path directory = Files.createDirectories(root.resolve("0.7.0-dev"));
    Files.writeString(root.resolve("latest.txt"), "0.7.0-dev\n");
    Files.writeString(directory.resolve("syncsrv-agent-linux-amd64"), "binary");
    Files.writeString(directory.resolve("syncsrv-agent-linux-amd64.tar.gz"), "package");
    var asset = new AgentDistributionController.Asset("linux", "amd64", "syncsrv-agent-linux-amd64", "0".repeat(64), 6,
        "syncsrv-agent-linux-amd64.tar.gz", "1".repeat(64), 7);
    var key=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var payload=com.oadig.syncsrv.shared.Json.MAPPER.createObjectNode();
    payload.put("version","0.7.0-dev").put("product","SyncSrvAgent").put("schema",1).put("channel","stable")
        .put("issuedAt",java.time.Instant.now().toString()).put("expiresAt",java.time.Instant.now().plusSeconds(3600).toString());
    payload.set("assets",com.oadig.syncsrv.shared.Json.MAPPER.valueToTree(java.util.List.of(asset)));
    byte[] raw=payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var signature=java.security.Signature.getInstance("Ed25519");signature.initSign(key.getPrivate());signature.update(raw);
    Files.writeString(directory.resolve("release.json"),com.oadig.syncsrv.shared.Json.write(java.util.Map.of("payload",java.util.Base64.getEncoder().encodeToString(raw),"signature",java.util.Base64.getEncoder().encodeToString(signature.sign()),"keyId","test")));
    byte[] publicRaw=java.util.Arrays.copyOfRange(key.getPublic().getEncoded(),12,44);
    var trust=com.oadig.syncsrv.shared.Json.MAPPER.valueToTree(java.util.Map.of("test",java.util.Base64.getEncoder().encodeToString(publicRaw)));
    var controller = new AgentDistributionController(root.toString(),trust);
    assertEquals("0.7.0-dev", controller.latest().getBody().data().version());
    assertEquals("no-store", controller.latest().getHeaders().getCacheControl());
    assertEquals(7, controller.release("0.7.0-dev", "syncsrv-agent-linux-amd64.tar.gz").getHeaders().getContentLength());
    Files.delete(directory.resolve("syncsrv-agent-linux-amd64.tar.gz"));
    assertThrows(ResponseStatusException.class, controller::latest);
    Files.writeString(root.resolve("latest.txt"), "../escape");
    assertThrows(ResponseStatusException.class, controller::latest);
  }

  @Test
  void sameInstallEndpointSelectsShellForRequestingClient() throws Exception {
    var controller = new AgentDistributionController("");
    String shell =
        new String(controller.install("curl/8").getBody().getInputStream().readAllBytes());
    String windows =
        new String(
            controller.install("SyncSrv-PowerShell").getBody().getInputStream().readAllBytes());
    assertTrue(shell.startsWith("#!/bin/sh"));
    assertTrue(windows.startsWith("param("));
    assertTrue(shell.contains("[Install]\nWantedBy="));
    assertEquals("no-store", controller.install("").getHeaders().getCacheControl());
  }

  @Test
  void mvcDownloadPreservesFilenameAndBytesInsteadOfFtxt() throws Exception {
    Path directory = Files.createDirectories(root.resolve("0.10.0-dev"));
    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
        .standaloneSetup(new AgentDistributionController(root.toString())).build();
    String[][] assets = {
      {"syncsrv-agent-windows-amd64.zip", "application/zip"},
      {"syncsrv-agent-linux-arm64.tar.gz", "application/gzip"},
      {"syncsrv-agent-darwin-arm64.tar.gz", "application/gzip"},
      {"syncsrv-agent-windows-amd64.exe", "application/octet-stream"},
      {"syncsrv-agent-linux-amd64", "application/octet-stream"},
      {"syncsrv-agent-synology-amd64.spk", "application/octet-stream"},
      {"syncsrv-agent-synology-arm64.spk.sha256", "text/plain"},
      {"syncsrv-agent-windows-amd64.zip.sha256", "text/plain"}
    };
    byte[] body = new byte[]{80, 75, 3, 4, 0, 1, 2};
    for (String[] asset : assets) {
      Files.write(directory.resolve(asset[0]), body);
      String url = "/agent/releases/0.10.0-dev/" + asset[0];
      var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url))
          .andReturn().getResponse();
      assertEquals(200, response.getStatus());
      assertEquals("attachment; filename=\"" + asset[0] + "\"", response.getHeader("Content-Disposition"));
      assertEquals(asset[1], response.getContentType());
      assertArrayEquals(body, response.getContentAsByteArray());
      var head = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(url))
          .andReturn().getResponse();
      assertEquals(response.getHeader("Content-Disposition"), head.getHeader("Content-Disposition"));
      assertEquals(Integer.toString(body.length), head.getHeader("Content-Length"));
    }
  }
}
