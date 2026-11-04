package com.oadig.syncsrv.modules.sync;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Ids;
import com.oadig.syncsrv.shared.Models.Node;
import java.util.*;

/** Signed per-path rows; legacy arrays migrate atomically on the first load. */
public final class CurrentIndex {
  private final Store store;

  public CurrentIndex(Store store) {
    this.store = store;
  }

  public static String kind(String jobId) {
    return Ids.textHash("current:" + jobId).substring(0, 32);
  }

  public record Format(int version, String jobId) {}

  public Map<String, Node> load(String jobId) throws Exception {
    synchronized (store) {
      Format format = store.get("current-index", jobId, Format.class);
      if (format == null) {
        Node[] legacy = store.get("current", jobId, Node[].class);
        List<Store.Change> changes = new ArrayList<>();
        if (legacy != null)
          for (Node n : legacy) changes.add(put(kind(jobId), Ids.textHash(n.path()), n));
        changes.add(put("current-index", jobId, new Format(1, jobId)));
        changes.add(remove("current", jobId));
        store.commit(
            "system",
            "index.migrated",
            jobId,
            Map.of("entries", legacy == null ? 0 : legacy.length),
            changes.toArray(Store.Change[]::new));
      } else if (format.version() != 1 || !format.jobId().equals(jobId)) {
        throw new IllegalStateException("当前索引格式不匹配");
      }
      Map<String, Node> nodes = new TreeMap<>();
      for (Node node : store.list(kind(jobId), Node.class)) nodes.put(node.path(), node);
      new DirectoryIndex(store).ensure(jobId, nodes);
      return nodes;
    }
  }

  public Node get(String jobId, String path) throws Exception {
    if (store.get("current-index", jobId, Format.class) == null) return load(jobId).get(path);
    return store.get(kind(jobId), Ids.textHash(path), Node.class);
  }

  public static Store.Change update(String jobId, String path, Node node) {
    return node == null
        ? remove(kind(jobId), Ids.textHash(path))
        : put(kind(jobId), Ids.textHash(path), node);
  }
}
