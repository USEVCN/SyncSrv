package com.oadig.syncsrv.platform.storage;

import java.nio.file.*;
import java.util.*;

public final class StoragePaths {
  private StoragePaths() {}

  public static String relative(String value) {
    if (value == null) throw new IllegalArgumentException("路径不能为空");
    if (value.isEmpty() || value.equals(".")) return "";
    if (value.startsWith("/")
        || value.contains("\\")
        || value.indexOf('\0') >= 0
        || value.contains("\r")
        || value.contains("\n")) throw new IllegalArgumentException("不安全路径");
    for (String part : value.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals(".."))
        throw new IllegalArgumentException("不安全路径");
    return value;
  }

  public static String parent(String path) {
    int i = path.lastIndexOf('/');
    return i < 0 ? "" : path.substring(0, i);
  }

  public static String name(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  public static String join(String root, String path) {
    relative(path);
    return root.replaceAll("/+$", "") + (path.isEmpty() ? "" : "/" + path);
  }

  public static Path canonical(Path value) throws Exception {
    Path p = value.toAbsolutePath().normalize();
    Deque<String> rest = new ArrayDeque<>();
    while (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
      if (p.getParent() == null) throw new IllegalArgumentException("目录不存在");
      rest.addFirst(p.getFileName().toString());
      p = p.getParent();
    }
    p = p.toRealPath();
    for (String part : rest) p = p.resolve(part);
    return p.normalize();
  }

  public static boolean overlap(String a, String b) {
    String x = a.replace('\\', '/').replaceAll("/+$", "");
    String y = b.replace('\\', '/').replaceAll("/+$", "");
    return x.equals(y) || x.startsWith(y + "/") || y.startsWith(x + "/");
  }
}
