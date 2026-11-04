package com.oadig.syncsrv.platform.persistence;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.zip.*;

/** Offline, no-overwrite H2 metadata backup and recovery. */
public final class MetadataTools {
  private MetadataTools() {}
  private static final List<String> KEYS = List.of("audit-private.key", "audit-public.key", "credentials.key");
  public static String url(Path data) {
    if (data.toString().contains(";")) throw new IllegalArgumentException("数据目录不能包含分号");
    return "jdbc:h2:file:" + data.toAbsolutePath().normalize().resolve("metadata") + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
  }
  public static void run(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException(
        "backup-metadata <目录> <新zip> | restore-metadata <zip> <新目录>");
    Path source = Path.of(args[1]).toAbsolutePath(), target = Path.of(args[2]).toAbsolutePath();
    switch (args[0]) {
      case "backup-metadata" -> backup(source, target);
      case "restore-metadata" -> {restore(source, target);try(Store restored=new Store(target)){com.oadig.syncsrv.modules.operations.RecoveryState.prepare(restored,"os:"+System.getProperty("user.name"));}}
      default -> throw new IllegalArgumentException("未知命令");
    }
    System.out.println("Metadata operation completed and signatures verified.");
  }
  static void verify(Path data) throws Exception {
    try (Store store = new Store(data);
        Connection db = DriverManager.getConnection(url(data), "sa", "");
        Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT DISTINCT kind FROM objects")) {
      store.verify();
      while (r.next()) store.list(r.getString(1), Map.class);
      try (Statement check = db.createStatement(); ResultSet bad = check.executeQuery(
          "SELECT COUNT(*) FROM object_signatures s LEFT JOIN objects o ON o.kind=s.kind AND o.id=s.id WHERE o.id IS NULL")) {
        bad.next(); if (bad.getLong(1) != 0) throw new IllegalStateException("孤立状态签名");
      }
    }
  }
  private static Path staging(Path target) throws Exception {
    if (Files.exists(target)) throw new IllegalArgumentException("目标必须不存在，禁止覆盖已有数据");
    return Files.createTempDirectory(target.getParent(), ".syncsrv-metadata-");
  }
  private static void publish(Path stage, Path target) throws Exception {
    verify(stage); Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
  }
  public static void backup(Path source, Path target) throws Exception {
    if (!Files.isRegularFile(source.resolve("metadata.mv.db"))) throw new IllegalArgumentException("找不到已有 H2 数据库");
    Path temp = Files.createTempFile(target.getParent(), ".syncsrv-backup-", ".zip");
    try (Store ignored = new Store(source);
        Connection db = DriverManager.getConnection(url(source), "sa", "")) {
      // Store holds the directory lock throughout; H2 online backup captures a consistent database.
      Path databaseZip = Files.createTempFile(target.getParent(), ".syncsrv-h2-", ".zip");
      try {
        try (Statement s = db.createStatement()) {
          s.execute("BACKUP TO '" + databaseZip.toString().replace("'", "''") + "'");
        }
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp));
            ZipInputStream in = new ZipInputStream(Files.newInputStream(databaseZip))) {
          ZipEntry entry = in.getNextEntry();
          if (entry == null || !entry.getName().equals("metadata.mv.db")) throw new IllegalStateException("H2备份格式异常");
          out.putNextEntry(new ZipEntry("metadata.mv.db")); in.transferTo(out); out.closeEntry();
          for (String key : KEYS) {
            out.putNextEntry(new ZipEntry(key)); Files.copy(source.resolve(key), out); out.closeEntry();
          }
        }
      } finally { Files.deleteIfExists(databaseZip); }
      Path check = Files.createTempDirectory(target.getParent(), ".syncsrv-backup-check-");
      restore(temp, check.resolve("verified"));
      try (var files = Files.walk(check)) {
        for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
      Files.createLink(target, temp);
    } finally { Files.deleteIfExists(temp); }
  }
  public static void restore(Path archive, Path target) throws Exception {
    Path stage = staging(target);
    Set<String> allowed = new HashSet<>(KEYS); allowed.add("metadata.mv.db");
    Set<String> seen = new HashSet<>();
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
      for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
        if (!allowed.contains(e.getName()) || !seen.add(e.getName())) throw new IllegalArgumentException("非法或重复备份条目");
        Files.copy(zip, stage.resolve(e.getName()));
      }
    }
    if (!seen.equals(allowed)) throw new IllegalArgumentException("备份缺少数据库或密钥");
    publish(stage, target);
  }
}
