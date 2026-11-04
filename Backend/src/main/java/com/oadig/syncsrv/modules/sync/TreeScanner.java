package com.oadig.syncsrv.modules.sync;

import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.Models.Node;
import java.io.IOException;
import java.util.*;

public final class TreeScanner {
  private final TransferService transfer;

  public TreeScanner(TransferService transfer) {
    this.transfer = transfer;
  }

  public Map<String, Node> scan(Storage s, TaskContext task, boolean managed, boolean contents)
      throws Exception {
    Storage.Item root = s.stat("");
    if (root == null || !root.directory() || root.symbolic())
      throw new IOException("根目录不可读，禁止据此推断文件已删除");
    Map<String, Node> nodes = new TreeMap<>();
    Set<String> folded = new HashSet<>();
    Deque<String> pending = new ArrayDeque<>();
    pending.add("");
    while (!pending.isEmpty()) {
      task.check();
      String parent = pending.removeFirst();
      for (Storage.Item item : s.list(parent)) {
        task.metrics.scanned();
        String path = parent.isEmpty() ? item.name() : parent + "/" + item.name();
        StoragePaths.relative(path);
        if (parent.isEmpty() && item.name().equalsIgnoreCase(".syncsrv")) {
          if (managed) continue;
          throw new IOException("源目录不能使用保留名称 .syncsrv");
        }
        if (item.name().contains("/") || item.symbolic())
          throw new IOException("不支持符号链接、重解析点或异常文件名: " + path);
        if (!folded.add(path.toLowerCase(Locale.ROOT)))
          throw new IOException("存在大小写冲突的路径: " + path);
        String hash = "";
        if (item.directory()) pending.addLast(path);
        else if (contents) {
          task.stage("HASHING", path);
          hash =
              s instanceof com.oadig.syncsrv.modules.discovery.AgentStorage source
                  ? source.hash(path)
                  : transfer.hash(s, path, item.size(), task);
          Storage.Item after = s.stat(path);
          if (after == null
              || after.symbolic()
              || after.directory()
              || after.size() != item.size()
              || after.modified() != item.modified())
            throw new IOException("扫描期间源文件变化，请重试: " + path);
        }
        nodes.put(
            path,
            new Node(
                path,
                item.directory(),
                item.directory() ? 0 : item.size(),
                item.modified(),
                hash,
                ""));
      }
    }
    return nodes;
  }

  @FunctionalInterface public interface Open { Storage open() throws Exception; }

  /** Enumerate metadata once, then hash independent files with private protocol sessions. */
  public Map<String, Node> scan(Storage s, TaskContext task, boolean managed, boolean contents,
      int limit, Open open) throws Exception {
    if (!contents || s instanceof com.oadig.syncsrv.modules.discovery.AgentStorage)
      return scan(s, task, managed, contents);
    return hashFiles(scan(s, task, managed, false), task, limit, open);
  }

  public Map<String, Node> scanPaths(Storage s, TaskContext task, Collection<String> paths,
      boolean managed, boolean contents, int limit, Open open) throws Exception {
    if (!contents || s instanceof com.oadig.syncsrv.modules.discovery.AgentStorage)
      return scanPaths(s, task, paths, managed, contents);
    return hashFiles(scanPaths(s, task, paths, managed, false), task, limit, open);
  }

  private Map<String, Node> hashFiles(Map<String, Node> nodes, TaskContext task, int limit, Open open)
      throws Exception {
    Map<String, Node> result = new java.util.concurrent.ConcurrentSkipListMap<>(nodes);
    List<Node> files = nodes.values().stream().filter(n -> !n.directory()).toList();
    ParallelWork.run(files, limit, task, () -> {
      Storage session = open.open();
      return new ParallelWork.Worker<Node>() {
        public void apply(Node node) throws Exception {
          task.check();
          task.progress.checkStarted();
          try {
            unchanged(session.stat(node.path()), node);
            task.stage("HASHING", node.path());
            String hash = transfer.hash(session, node.path(), node.size(), task);
            unchanged(session.stat(node.path()), node);
            result.put(node.path(), new Node(node.path(), false, node.size(), node.modified(), hash, ""));
          } finally { task.progress.checkFinished(); }
        }
        public void close() throws Exception { session.close(); }
      };
    });
    return new TreeMap<>(result);
  }

  private static void unchanged(Storage.Item item, Node node) throws IOException {
    if (item == null || item.symbolic() || item.directory()
        || item.size() != node.size() || item.modified() != node.modified())
      throw new IOException("扫描期间文件变化，请重试: " + node.path());
  }

  public static boolean sameContent(Node a, Node b) {
    return a != null
        && b != null
        && a.directory() == b.directory()
        && (a.directory() || (a.size() == b.size() && a.hash().equals(b.hash())));
  }

  public static int depth(String path) {
    return path.split("/").length;
  }

  /** Enumerates only invalidated subtrees. Missing paths are checked against a readable root. */
  public Map<String, Node> scanPaths(
      Storage s, TaskContext task, Collection<String> paths, boolean managed, boolean contents)
      throws Exception {
    Storage.Item root = s.stat("");
    if (root == null || !root.directory() || root.symbolic())
      throw new IOException("根目录不可读，禁止据此推断文件已删除");
    Map<String, Node> nodes = new TreeMap<>();
    Deque<String> pending = new ArrayDeque<>(paths);
    Set<String> visited = new HashSet<>();
    while (!pending.isEmpty()) {
      task.check();
      String path = pending.removeFirst();
      StoragePaths.relative(path);
      if (!visited.add(path)) continue;
      if (path.equalsIgnoreCase(".syncsrv")
          || path.toLowerCase(Locale.ROOT).startsWith(".syncsrv/")) {
        if (managed) continue;
        throw new IOException("源目录不能使用保留名称 .syncsrv");
      }
      Storage.Item item = s.stat(path);
      if (item == null) continue;
      task.metrics.scanned();
      if (item.symbolic()) throw new IOException("不支持符号链接或重解析点: " + path);
      String hash = "";
      if (item.directory()) {
        for (Storage.Item child : s.list(path)) {
          if (child.name().contains("/")) throw new IOException("异常文件名");
          pending.addLast(path.isEmpty() ? child.name() : path + "/" + child.name());
        }
      } else if (contents) {
        task.stage("HASHING", path);
        hash =
            s instanceof com.oadig.syncsrv.modules.discovery.AgentStorage source
                ? source.hash(path)
                : transfer.hash(s, path, item.size(), task);
        Storage.Item after = s.stat(path);
        if (after == null
            || after.symbolic()
            || after.directory()
            || after.size() != item.size()
            || after.modified() != item.modified()) throw new IOException("读取期间文件变化: " + path);
      }
      if (!path.isEmpty())
        nodes.put(
            path,
            new Node(
                path,
                item.directory(),
                item.directory() ? 0 : item.size(),
                item.modified(),
                hash,
                ""));
    }
    return nodes;
  }
}
