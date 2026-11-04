package com.oadig.syncsrv.modules.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.oadig.syncsrv.shared.Json;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseContractTest {
  static final URI FEED = URI.create("https://repo.example/api/v1/projects/syncsrv/channels/stable/manifest?os=linux&arch=x64");
  static final String ID = "11111111-1111-1111-1111-111111111111";
  static class Fixture {
    final KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    final ObjectNode keys = Json.MAPPER.createObjectNode(), payload = Json.MAPPER.createObjectNode(), index = Json.MAPPER.createObjectNode();
    byte[] sidecar;
    Fixture() throws Exception {
      byte[] encoded = key.getPublic().getEncoded();
      keys.put("test", Base64.getEncoder().encodeToString(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length)));
      payload.put("product", "SyncSrv").put("schema", 1).put("channel", "stable").put("version", "0.4.3").put("target", "linux-x64")
          .put("distribution", "relshub-v1").put("sourceMin", "0.4.0").put("sourceMax", "0.4.2").put("schemaMin", 1).put("schemaMax", 1)
          .put("rollbackMinimumVersion", "0.4.0").put("applicationSha256", "b".repeat(64))
          .put("issuedAt", Instant.now().minusSeconds(60).toString()).put("expiresAt", Instant.now().plusSeconds(86400).toString());
      payload.putObject("asset").put("repository", "https://repo.example").put("name", "syncsrv-0.4.3-linux-x64.tar.gz").put("size", 10).put("sha256", "a".repeat(64));
      index.put("releaseId", ID).put("algorithm", "Ed25519");
      ObjectNode spec = index.putObject("spec");
      spec.put("project", "syncsrv").put("version", "0.4.3").put("buildNumber", "test-001").put("os", "linux").put("arch", "x64")
          .put("notes", "Release contract fixture").put("keyId", "test").put("sourceMin", "0.4.0").put("sourceMax", "0.4.2")
          .put("schemaMin", 1).put("schemaMax", 1).put("targetSchema", 1).put("rollbackAllowed", true).putNull("intermediate");
      spec.putArray("files"); index.putArray("files"); rebuild();
    }
    String signature(byte[] bytes) throws Exception {
      var proof = Signature.getInstance("Ed25519"); proof.initSign(key.getPrivate()); proof.update(bytes);
      return Base64.getEncoder().encodeToString(proof.sign());
    }
    void signIndex() throws Exception {
      byte[] canonical = ReleaseContract.canonical(index.path("spec"));
      index.put("canonicalBase64", Base64.getEncoder().encodeToString(canonical)).put("signature", signature(canonical));
    }
    void rebuild() throws Exception {
      byte[] raw = Json.MAPPER.writeValueAsBytes(payload);
      sidecar = Json.MAPPER.writeValueAsBytes(Map.of("payload", Base64.getEncoder().encodeToString(raw), "keyId", "test", "signature", signature(raw)));
      var signed = (ArrayNode)index.path("spec").path("files"); signed.removeAll();
      signed.addObject().put("name", ReleaseContract.SIDECAR).put("kind", "INSTALLER").putNull("sourceVersion")
          .put("size", sidecar.length).put("sha256", ReleaseContract.sha(sidecar));
      signed.addObject().put("name", "syncsrv-0.4.3-linux-x64.tar.gz").put("kind", "FULL").putNull("sourceVersion").put("size", 10).put("sha256", "a".repeat(64));
      var rows = (ArrayNode)index.path("files"); rows.removeAll();
      for (int i = 0; i < signed.size(); i++) {
        ObjectNode row = signed.get(i).deepCopy(); row.put("sourceVersion", "").put("url", "/api/v1/projects/syncsrv/channels/stable/releases/" + ID + "/artifacts/" + (i + 1)); rows.add(row);
      }
      signIndex();
    }
    JsonNode resolve() throws Exception { return ReleaseContract.resolve(FEED, index, sidecar, keys, Instant.now()); }
    void publicPaths(String prefix) { int n = 1; for (var row : index.path("files")) ((ObjectNode)row).put("url", prefix + ID + "/files/" + n++); }
  }
  @Test void authenticatesActualApiAndBothPublicPathContracts() throws Exception {
    var f = new Fixture(); assertEquals("0.4.3", f.resolve().path("version").asText());
    for (String prefix : List.of("/downloads/releases/", "/downloads/releases/syncsrv/")) {
      f.publicPaths(prefix); assertTrue(f.resolve().path("asset").path("url").asText().contains(prefix));
    }
  }
  @Test void rejectsOuterSignatureCanonicalAndUnsignedDescriptorTampering() throws Exception {
    var f = new Fixture(); ((ObjectNode)f.index.path("spec")).put("notes", "changed");
    assertThrows(SecurityException.class, f::resolve);
    f.signIndex(); f.index.put("signature", Base64.getEncoder().encodeToString(new byte[64])); assertThrows(SecurityException.class, f::resolve);
    f = new Fixture(); ((ObjectNode)f.index.path("files").get(0)).put("sha256", "c".repeat(64)); var changed = f;
    assertThrows(IllegalArgumentException.class, changed::resolve);
  }
  @Test void rejectsCrossOriginChannelReleaseTraversalAndQueryBeforeFetching() throws Exception {
    for (String path : List.of("https://evil.example/file", "/api/v1/projects/syncsrv/channels/beta/releases/" + ID + "/artifacts/1",
        "/downloads/releases/22222222-2222-2222-2222-222222222222/files/one", "/downloads/releases/" + ID + "/files/%2e%2e",
        "/downloads/releases/" + ID + "/files/one?redirect=1", "/downloads/releases/" + ID + "/files/one#fragment")) {
      var f = new Fixture(); ((ObjectNode)f.index.path("files").get(0)).put("url", path); assertThrows(IllegalArgumentException.class, f::resolve, path);
    }
  }
  @Test void sidecarDigestAndNativeSignatureAreIndependentlyChecked() throws Exception {
    var f = new Fixture(); f.sidecar[0] ^= 1; assertThrows(IllegalArgumentException.class, f::resolve);
    f = new Fixture(); ObjectNode signed = (ObjectNode)Json.MAPPER.readTree(f.sidecar);
    signed.put("signature", Base64.getEncoder().encodeToString(new byte[64])); f.sidecar = Json.MAPPER.writeValueAsBytes(signed);
    for (var group : List.of(f.index.path("files"), f.index.path("spec").path("files"))) {
      ((ObjectNode)group.get(0)).put("size", f.sidecar.length).put("sha256", ReleaseContract.sha(f.sidecar));
    }
    f.signIndex(); var bad = f; assertThrows(SecurityException.class, bad::resolve);
  }
  @Test void rejectsSignedButInconsistentNativeIdentityAndCompatibility() throws Exception {
    for (String field : List.of("channel", "product", "target", "sourceMax", "schemaMax")) {
      var f = new Fixture(); if (field.equals("schemaMax")) f.payload.put(field, 2); else f.payload.put(field, "other"); f.rebuild();
      assertThrows(Exception.class, f::resolve, field);
    }
    var f = new Fixture(); f.payload.put("expiresAt", Instant.now().minusSeconds(1).toString()); f.rebuild();
    assertThrows(SecurityException.class, f::resolve);
  }
  @Test void signedBetaMustMatchTheConfiguredFeedAndCannotEnterStable() throws Exception {
    var f = new Fixture(); f.payload.put("channel", "beta"); f.rebuild();
    assertThrows(SecurityException.class, f::resolve);
    for (var row : f.index.path("files")) ((ObjectNode)row).put("url", row.path("url").asText().replace("/stable/", "/beta/"));
    assertEquals("beta", ReleaseContract.resolve(URI.create(FEED.toString().replace("/stable/", "/beta/")), f.index, f.sidecar, f.keys, Instant.now()).path("channel").asText());
  }
  @Test void usesNumericVersionsAndPreservesSameVersionChecksOutsideSourceRange() throws Exception {
    var f = new Fixture(); assertEquals("", ReleaseContract.compatibility(f.payload, "0.4.3"));
    assertEquals("", ReleaseContract.compatibility(f.payload, "0.4.2"));
    assertFalse(ReleaseContract.compatibility(f.payload, "0.3.9").isEmpty());
    assertFalse(ReleaseContract.compatibility(f.payload, "0.5.0").isEmpty());
    assertTrue(ReleaseContract.compare("0.10.0", "0.9.9") > 0);
    assertThrows(IllegalArgumentException.class, () -> ReleaseContract.compare("0.04.3", "0.4.3"));
  }
  @Test void canonicalMatchesTheRelsHubFixedFieldOrder() throws Exception {
    var f = new Fixture(); String text = new String(ReleaseContract.canonical(f.index.path("spec")), StandardCharsets.UTF_8);
    assertTrue(text.startsWith("relshub-release-v1\nc3luY3Nydg\nMC40LjM\ndGVzdC0wMDE\nbGludXg\neDY0\n"));
    assertTrue(text.contains("\n~\nMQ\nMQ\nMQ\ndHJ1ZQ\ndGVzdA\nMg\n"));
  }
  @Test void signedSchemaAndRollbackFieldsMustHaveTheContractTypes() throws Exception {
    for (String field : List.of("schemaMin", "schemaMax", "targetSchema", "rollbackAllowed")) {
      var f = new Fixture(); ((ObjectNode)f.index.path("spec")).put(field, field.equals("rollbackAllowed") ? "true" : "1"); f.signIndex();
      assertThrows(IllegalArgumentException.class, f::resolve, field);
    }
  }
  @Test void rejectsInvertedNativeValidityEvenWithinClockTolerance() throws Exception {
    var f = new Fixture(); f.payload.put("issuedAt", Instant.now().plusSeconds(200).toString()).put("expiresAt", Instant.now().plusSeconds(100).toString()); f.rebuild();
    assertThrows(SecurityException.class, f::resolve);
  }
  @Test void cannotSubstituteAnHttpsAliasForTheSignedIntranetRepository() throws Exception {
    var f = new Fixture(); ((ObjectNode)f.payload.path("asset")).put("repository", "http://192.168.50.118:18760"); f.rebuild();
    assertTrue(assertThrows(IllegalArgumentException.class, f::resolve).getMessage().contains("签名仓库来源不匹配"));
  }
}
