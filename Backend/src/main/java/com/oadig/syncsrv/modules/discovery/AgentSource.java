package com.oadig.syncsrv.modules.discovery;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** A published manifest is a complete, immutable observation of the announced scopes. */
public final class AgentSource {
  private AgentSource() {}

  public record State(long revision, String lastFullScan, String publishedAt, boolean direct) {
    public State(long revision, String lastFullScan, String publishedAt) {
      this(revision, lastFullScan, publishedAt, false);
    }
  }

  public static String kind(String job) {
    return Ids.textHash("agent-source:" + job).substring(0, 32);
  }

  public static State state(Store store, String job) throws Exception {
    return store.get("agent-source-state", job, State.class);
  }

  public static Map<String, Node> load(Store store, String job) throws Exception {
    Map<String, Node> result = new TreeMap<>();
    for (Node n : store.list(kind(job), Node.class)) result.put(n.path(), n);
    return result;
  }

  public static boolean needsFull(Store store, Job job) throws Exception {
    State s = state(store, job.id);
    return s == null
        || Instant.now().isAfter(Instant.parse(s.lastFullScan()).plusSeconds(job.reconcileSeconds));
  }

  public static String blob(String job, String hash) {
    return ".syncsrv/source/" + Ids.textHash(job) + "/" + hash;
  }

  /** Targets without offset I/O use durable center storage until the full object is verified. */
  public static Storage staging(Store store, ConfigurationService config, Job job)
      throws Exception {
    Storage target = StorageFactory.open(config.endpoint(job.targetId));
    if (target.capabilities().offsetRead() && target.capabilities().offsetWrite()) return target;
    target.close();
    if (store.directory == null) throw new IOException("中心本地暂存目录不可用");
    try (LocalStorage data = new LocalStorage(store.directory)) {
      data.mkdirs("agent-upload-staging");
    }
    return new LocalStorage(store.directory.resolve("agent-upload-staging"));
  }

  public static void validate(Node n) {
    if (n == null || n.path() == null || n.path().isBlank() || n.path().length() > 4096)
      throw new IllegalArgumentException("源端条目路径不正确");
    if(!StoragePaths.relative(n.path()).equals(n.path()))
      throw new IllegalArgumentException("源端条目不能使用根目录别名");
    if (n.path().equalsIgnoreCase(".syncsrv")
        || n.path().toLowerCase(Locale.ROOT).startsWith(".syncsrv/"))
      throw new IllegalArgumentException("源目录不能使用保留名称 .syncsrv");
    if (n.size() < 0
        || n.hash() == null
        || (n.directory() ? n.size() != 0 : !n.hash().matches("[a-f0-9]{64}")))
      throw new IllegalArgumentException("源端条目大小或哈希不正确");
  }
}
