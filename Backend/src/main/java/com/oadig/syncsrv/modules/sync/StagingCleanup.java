package com.oadig.syncsrv.modules.sync;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.IOException;
import java.util.*;

/**
 * Only abandoned operation staging files are eligible; 备份库与归档库 versions and resumable intents remain.
 */
public final class StagingCleanup {
  public record Result(long files, long bytes, int retained) {}

  private final Store store;
  private final ConfigurationService config;

  public StagingCleanup(Store store, ConfigurationService config) {
    this.store = store;
    this.config = config;
  }

  public Result clean(Job job, String actor, String subject) throws Exception {
    Set<String> protectedPaths = new HashSet<>();
    for (Operation op : store.list("operation", Operation.class))
      if (op.jobId.equals(job.id)) protectedPaths.add(op.temp);
    String root = ".syncsrv/staging/" + job.id;
    long files = 0, bytes = 0;
    int retained = 0;
    try (Storage target = StorageFactory.open(config.endpoint(job.targetId))) {
      for (String path : List.of(".syncsrv", ".syncsrv/staging", root)) {
        Storage.Item parent = target.stat(path);
        if (parent == null) return new Result(0, 0, 0);
        if (!parent.directory() || parent.symbolic()) throw new IOException("暂存目录类型异常，停止清理");
      }
      for (Storage.Item item : target.list(root)) {
        String path = root + "/" + item.name();
        if (item.directory()
            || item.symbolic()
            || !item.name().matches("[a-f0-9]{32}\\.part")
            || protectedPaths.contains(path)) {
          retained++;
          continue;
        }
        store.commit(
            actor,
            "staging.remove_intent",
            subject,
            Map.of("jobId", job.id, "path", path, "bytes", item.size()));
        target.delete(path, false);
        store.commit(
            actor,
            "staging.removed",
            subject,
            Map.of("jobId", job.id, "path", path, "bytes", item.size()));
        files++;
        bytes += item.size();
      }
    }
    Result result = new Result(files, bytes, retained);
    store.commit(actor, "staging.cleanup_completed", subject, result);
    return result;
  }
}
