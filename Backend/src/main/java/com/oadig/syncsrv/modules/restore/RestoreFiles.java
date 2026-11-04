package com.oadig.syncsrv.modules.restore;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.modules.sync.FileBatch;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.IOException;
import java.util.*;

/** Private source/destination sessions per worker; only verified files are published into D. */
final class RestoreFiles {
  private RestoreFiles() {}

  static void copy(List<RestoreItem> items, RestoreDestination target, String owner, int limit,
      ConfigurationService config, Store store, TransferService transfer, FileBatch.Open open,
      TaskContext task) throws Exception {
    Map<String, Endpoint> endpoints = new HashMap<>();
    Set<String> parents = new TreeSet<>();
    for (RestoreItem item : items) {
      if (!endpoints.containsKey(item.endpointId()))
        endpoints.put(item.endpointId(), config.endpoint(item.endpointId()));
      parents.add(StoragePaths.parent(item.path()));
    }
    target.mkdirs(".syncsrv/parts");
    for (String parent : parents) { task.check(); target.mkdirs(parent); }
    task.progress.concurrency(Math.max(1, Math.min(16, limit)));
    ParallelWork.run(items, limit, task, () -> {
      RestoreDestination destination = target.fork();
      Map<String, Storage> sources = new HashMap<>();
      return new ParallelWork.Worker<RestoreItem>() {
        public void apply(RestoreItem item) throws Exception {
          task.check();
          task.progress.begin(item.path(), "RESTORE", item.size());
          try (var measured = task.metrics.operation(item.path(), "RESTORE")) {
            try {
              task.stage("RESTORING", item.path());
              Storage source = sources.get(item.endpointId());
              if (source == null) {
                source = open.open(endpoints.get(item.endpointId()));
                sources.put(item.endpointId(), source);
              }
              Storage.Item existing = destination.stat(item.path());
              if (existing != null) {
                if (existing.symbolic() || existing.directory() || existing.size() != item.size()
                    || !transfer.hash(destination, item.path(), item.size(), task).equals(item.hash()))
                  throw new IOException("恢复目录已有不同内容，禁止覆盖: " + item.path());
              } else {
                String temp = ".syncsrv/parts/" + item.versionId() + ".part";
                transfer.copy(source, item.location(), destination, temp, item.size(), item.hash(),
                    owner + item.versionId(), task);
                destination.move(temp, item.path());
              }
              store.commit(task.run.actor, "restore.file_verified", task.run.id, item);
              task.done();
            } catch (Exception error) {
              measured.failed(error, task.cancelled.get());
              task.progress.finishFile(task.cancelled.get() ? "PAUSED" : "FAILED",
                  TaskManager.safeMessage(error));
              throw error;
            }
          }
        }
        public void close() throws Exception {
          Exception failure = null;
          List<Storage> sessions = new ArrayList<>(sources.values());
          sessions.add(destination);
          for (Storage session : sessions) try { session.close(); }
          catch (Exception error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
          }
          if (failure != null) throw failure;
        }
      };
    });
  }
}
