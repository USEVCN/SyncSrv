package com.oadig.syncsrv.modules.audit;

import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

public final class EvidenceVerifier {
  private EvidenceVerifier() {}

  public static AuditHead verify(Path evidence, String trustedPublicKey) throws Exception {
    PublicKey key =
        KeyFactory.getInstance("Ed25519")
            .generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(trustedPublicKey.trim())));
    try (BufferedReader reader = Files.newBufferedReader(evidence)) {
      String first = reader.readLine();
      if (first == null) throw new IOException("空审计文件");
      EvidenceHeader header = Json.read(first, EvidenceHeader.class);
      if (!header.format().equals("syncsrv-java-audit-v1")
          || !header.head().publicKey().equals(trustedPublicKey.trim())
          || !Store.validSignature(key, Ids.hash(Json.bytes(header.head())), header.signature()))
        throw new IOException("审计头或可信公钥校验失败");
      long sequence = 0;
      String previous = "", line;
      while ((line = reader.readLine()) != null) {
        AuditEvent event = Json.read(line, AuditEvent.class);
        if (event.sequence() != sequence + 1
            || !event.previous().equals(previous)
            || !Store.eventHash(event).equals(event.hash())
            || !Store.validSignature(key, event.hash(), event.signature()))
          throw new IOException("事件校验失败: " + event.sequence());
        sequence = event.sequence();
        previous = event.hash();
      }
      if (sequence != header.head().sequence() || !previous.equals(header.head().head()))
        throw new IOException("审计导出被截断或与已签名链头不一致");
      return header.head();
    }
  }

  public static void run(String[] args) throws Exception {
    if (args.length != 3)
      throw new IllegalArgumentException("用法: verify-audit <audit.jsonl> <独立留存的 audit-public.txt>");
    System.out.println(Json.write(verify(Path.of(args[1]), Files.readString(Path.of(args[2])))));
  }
}
