package com.oadig.syncsrv.modules.discovery;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.sync.CurrentIndex;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.util.*;

/** Files arrive from the source agent into B's private staging area, never a remote A share. */
public final class AgentStorage implements Storage {
  private final String job;
  private final Store store;
  private final ConfigurationService config;
  private final Job definition;
  private final boolean direct;
  private final Map<String, Node> nodes, current;
  private final Endpoint targetEndpoint;
  private final Map<String, List<Item>> children;
  private Storage target, staging;
  private boolean closed;

  public AgentStorage(Store store, ConfigurationService config, Job job) throws Exception {
    this(store, config, job, null);
  }

  /** Reuse the exact source manifest observed by this execution, with private I/O sessions. */
  public synchronized AgentStorage fork() throws Exception {
    ensureOpen();
    return new AgentStorage(store, config, definition, this);
  }

  private AgentStorage(Store store, ConfigurationService config, Job job, AgentStorage snapshot)
      throws Exception {
    this.job = job.id; this.store = store; this.config = config; this.definition = job;
    if (snapshot != null) {
      direct = snapshot.direct; nodes = snapshot.nodes; current = snapshot.current;
    } else synchronized (store) {
      if (AgentSource.state(store, job.id) == null) throw new IOException("等待源端上传首次基线");
      direct = AgentSource.state(store, job.id).direct();
      nodes = AgentSource.load(store, job.id);
      current = new CurrentIndex(store).load(job.id);
    }
    targetEndpoint = snapshot == null ? config.endpoint(job.targetId) : snapshot.targetEndpoint;
    if (snapshot != null) children = snapshot.children;
    else {
      Map<String, List<Item>> index = new HashMap<>();
      for (Node n : nodes.values())
        index.computeIfAbsent(StoragePaths.parent(n.path()), key -> new ArrayList<>()).add(item(n));
      index.replaceAll((key, value) -> List.copyOf(value));
      children = Map.copyOf(index);
    }
  }

  private void ensureOpen() throws IOException {
    if (closed) throw new IOException("源端文件会话已关闭");
  }

  private synchronized Storage target() throws Exception {
    ensureOpen();
    if (target == null) target = StorageFactory.open(targetEndpoint);
    return target;
  }

  private synchronized Storage staging() throws Exception {
    ensureOpen();
    if (staging == null)
      staging = direct ? target() : AgentSource.staging(store, config, definition);
    return staging;
  }

  public boolean direct() {
    return direct;
  }

  public void stage(String path, Storage b, String temporary) throws Exception {
    Node n = nodes.get(path);
    String from = DirectUploadService.path(job, n);
    Item received = b.stat(from);
    if (received == null
        || received.symbolic()
        || received.directory()
        || received.size() != n.size()) throw new IOException("已校验的备份库直传文件不可用");
    b.mkdirs(StoragePaths.parent(temporary));
    b.move(from, temporary);
  }

  public String hash(String path) {
    return nodes.get(path).hash();
  }

  public Item stat(String path) {
    StoragePaths.relative(path);
    if (path.isEmpty()) return new Item("", true, 0, 0, false);
    Node n = nodes.get(path);
    return n == null ? null : item(n);
  }

  private Item item(Node n) {
    return new Item(StoragePaths.name(n.path()), n.directory(), n.size(), n.modified(), false);
  }

  public List<Item> list(String directory) {
    StoragePaths.relative(directory);
    return children.getOrDefault(directory, List.of());
  }

  public InputStream read(String path, long offset) throws Exception {
    StoragePaths.relative(path);
    Node n = nodes.get(path);
    if (n == null || n.directory()) throw new FileNotFoundException(path);
    String staged = direct ? DirectUploadService.path(job, n) : AgentSource.blob(job, n.hash());
    Storage source = staging();
    if (source.stat(staged) != null) return source.read(staged, offset);
    Node installed = current.get(path);
    if (installed != null && installed.hash().equals(n.hash()) && installed.size() == n.size())
      return target().read(path, offset);
    throw new IOException("源端内容尚未上传完整: " + path);
  }

  public void write(String p, long o, InputStream in, long n) {
    throw new UnsupportedOperationException();
  }

  public void mkdirs(String p) {
    throw new UnsupportedOperationException();
  }

  public void move(String a, String b) {
    throw new UnsupportedOperationException();
  }

  public void delete(String p, boolean d) {
    throw new UnsupportedOperationException();
  }

  public Capabilities capabilities() {
    return new Capabilities(true, false, false, "源端已验证版本只读");
  }

  public synchronized void close() throws Exception {
    if (closed) return;
    closed = true;
    Exception failure = null;
    try {
      if (staging != null) staging.close();
    } catch (Exception e) { failure = e; }
    try {
      if (target != null && target != staging) target.close();
    } catch (Exception e) {
      if (failure == null) failure = e;
      else failure.addSuppressed(e);
    }
    if (failure != null) throw failure;
  }
}
