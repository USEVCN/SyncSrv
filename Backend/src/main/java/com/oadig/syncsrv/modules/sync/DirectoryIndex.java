package com.oadig.syncsrv.modules.sync;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.StoragePaths;
import com.oadig.syncsrv.shared.Ids;
import com.oadig.syncsrv.shared.Models.Node;
import java.util.*;

/** Aggregates describe committed B content, not an unverified source filesystem timestamp. */
public final class DirectoryIndex {
  public record Stats(
      String path, long files, long directories, long logicalBytes, String updatedAt) {}

  public record Format(int version) {}

  private final Store store;

  public DirectoryIndex(Store store) {
    this.store = store;
  }

  private static String kind(String job) {
    return Ids.textHash("directories:" + job).substring(0, 32);
  }

  private static Stats empty(String path) {
    return new Stats(path, 0, 0, 0, Ids.now());
  }

  private static List<String> parents(String path) {
    List<String> result = new ArrayList<>();
    do {
      path = StoragePaths.parent(path);
      result.add(path);
    } while (!path.isEmpty());
    return result;
  }

  public List<Stats> list(String job) throws Exception {
    return store.list(kind(job), Stats.class);
  }

  public void ensure(String job, Map<String, Node> nodes) throws Exception {
    synchronized (store) {
      if (store.get("directory-index", job, Format.class) != null) return;
      Map<String, Stats> totals = new TreeMap<>();
      totals.put("", empty(""));
      for (Node n : nodes.values()) {
        if (n.directory()) totals.putIfAbsent(n.path(), empty(n.path()));
        for (String parent : parents(n.path())) {
          Stats s = totals.getOrDefault(parent, empty(parent));
          totals.put(
              parent,
              new Stats(
                  parent,
                  s.files() + (n.directory() ? 0 : 1),
                  s.directories() + (n.directory() ? 1 : 0),
                  s.logicalBytes() + (n.directory() ? 0 : n.size()),
                  Ids.now()));
        }
      }
      List<Store.Change> changes = new ArrayList<>();
      for (Stats s : totals.values()) changes.add(put(kind(job), Ids.textHash(s.path()), s));
      changes.add(put("directory-index", job, new Format(1)));
      store.commit(
          "system",
          "directories.indexed",
          job,
          Map.of("directories", totals.size()),
          changes.toArray(Store.Change[]::new));
    }
  }

  public List<Store.Change> changes(String job, String path, Node after) throws Exception {
    Node before = store.get(CurrentIndex.kind(job), Ids.textHash(path), Node.class);
    long files =
        (after != null && !after.directory() ? 1 : 0)
            - (before != null && !before.directory() ? 1 : 0);
    long dirs =
        (after != null && after.directory() ? 1 : 0)
            - (before != null && before.directory() ? 1 : 0);
    long bytes =
        (after != null && !after.directory() ? after.size() : 0)
            - (before != null && !before.directory() ? before.size() : 0);
    List<String> ancestors = parents(path);
    Map<String, Stats> stored =
        store.getMany(kind(job), ancestors.stream().map(Ids::textHash).toList(), Stats.class);
    List<Store.Change> result = new ArrayList<>();
    for (String parent : ancestors) {
      Stats s = stored.getOrDefault(Ids.textHash(parent), empty(parent));
      Stats next =
          new Stats(
              parent,
              s.files() + files,
              s.directories() + dirs,
              s.logicalBytes() + bytes,
              Ids.now());
      if (next.files() < 0 || next.directories() < 0 || next.logicalBytes() < 0)
        throw new IllegalStateException("目录统计失效，停止提交");
      result.add(put(kind(job), Ids.textHash(parent), next));
    }
    if (after != null && after.directory() && (before == null || !before.directory()))
      result.add(put(kind(job), Ids.textHash(path), empty(path)));
    if (before != null && before.directory() && (after == null || !after.directory()))
      result.add(remove(kind(job), Ids.textHash(path)));
    return result;
  }
}
