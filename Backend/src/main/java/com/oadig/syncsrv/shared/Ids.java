package com.oadig.syncsrv.shared;

import java.security.*;
import java.time.Instant;
import java.util.*;

public final class Ids {
  private Ids() {}

  public static String next() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  public static String now() {
    return Instant.now().toString();
  }

  public static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hash(byte[] data) {
    return HexFormat.of().formatHex(digest().digest(data));
  }

  public static String textHash(String value) {
    return hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
