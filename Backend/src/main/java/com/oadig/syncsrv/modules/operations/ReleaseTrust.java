package com.oadig.syncsrv.modules.operations;

import com.oadig.syncsrv.shared.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;

/** Verifies publisher identity before parsing or running any downloaded code. */
public final class ReleaseTrust {
  private ReleaseTrust() {}
  public static JsonNode verify(JsonNode envelope, String product) throws Exception {
    try (var in = ReleaseTrust.class.getResourceAsStream("/release-trust.json")) {
      return verify(envelope, product, Json.MAPPER.readTree(in), Instant.now());
    }
  }
  public static JsonNode verify(JsonNode e, String product, JsonNode keys, Instant now) throws Exception {
    return verify(e, product, keys, now, "stable");
  }
  public static JsonNode verify(JsonNode e, String product, JsonNode keys, Instant now, String channel) throws Exception {
    byte[] raw = Base64.getDecoder().decode(e.path("payload").asText());
    if (raw.length > 65536) throw new SecurityException("Release manifest too large");
    authenticate(raw, e.path("signature").asText(), e.path("keyId").asText(), keys);
    JsonNode p = Json.MAPPER.readTree(raw);
    Instant issued = Instant.parse(p.path("issuedAt").asText()), expires = Instant.parse(p.path("expiresAt").asText());
    if (!Set.of("stable", "beta").contains(channel) || !product.equals(p.path("product").asText()) || !channel.equals(p.path("channel").asText())
        || p.path("schema").asInt() != 1 || issued.isAfter(now.plusSeconds(300))
        || !expires.isAfter(now) || !expires.isAfter(issued) || expires.isAfter(issued.plusSeconds(370L*86400)))
      throw new SecurityException("Incompatible or expired release");
    return p;
  }
  public static JsonNode keys() throws Exception {
    try (var in = ReleaseTrust.class.getResourceAsStream("/release-trust.json")) {
      return Json.MAPPER.readTree(in);
    }
  }
  static void authenticate(byte[] raw, String proof, String keyId, JsonNode keys) throws Exception {
    byte[] key = Base64.getDecoder().decode(keys.path(keyId).asText());
    if (key.length != 32) throw new SecurityException("Untrusted release publisher");
    byte[] der = new byte[44];
    System.arraycopy(java.util.HexFormat.of().parseHex("302a300506032b6570032100"),0,der,0,12);
    System.arraycopy(key,0,der,12,32);
    Signature signature = Signature.getInstance("Ed25519");
    signature.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der)));
    signature.update(raw);
    if (!signature.verify(Base64.getDecoder().decode(proof)))
      throw new SecurityException("Release publisher signature rejected");
  }
  public static void command(String[] args) throws Exception {
    if (args.length != 3 && args.length != 4) throw new IllegalArgumentException("verify-release FILE PRODUCT [CHANNEL]");
    if (Files.size(Path.of(args[1])) > 131072) throw new SecurityException("Manifest too large");
    System.out.println(verify(Json.MAPPER.readTree(Files.readString(Path.of(args[1]))), args[2], keys(), Instant.now(), args.length == 4 ? args[3] : "stable"));
  }
}
