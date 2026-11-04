package com.oadig.syncsrv.modules.sync;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.AgentStorage;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.Models.*;
import java.util.*;

/** Only independent files enter this batch; directory operations stay behind phase barriers. */
public final class FileBatch {
  @FunctionalInterface public interface Open { Storage open(Endpoint endpoint) throws Exception; }
  private final ConfigurationService config;
  private final FileOperations operations;
  private final Open open;

  public FileBatch(ConfigurationService config, FileOperations operations, Open open) {
    this.config = config; this.operations = operations; this.open = open;
  }

  public void apply(List<Node> entries, String kind, Job job, Storage source, Storage b, Storage c,
      Map<String, Node> current, TaskContext task) throws Exception {
    if (entries.isEmpty()) return;
    // Create shared parents once, before workers, including on protocols whose mkdir is not atomic.
    task.stage(kind.equals("DELETE") ? "ARCHIVING" : "TRANSFERRING", "");
    Set<String> parents = new TreeSet<>();
    for (Node entry : entries) {
      Node old = current.get(entry.path());
      if (old != null && !old.directory())
        parents.add(StoragePaths.join(job.id + "/versions", StoragePaths.parent(entry.path())));
    }
    for (String parent : parents) { task.check(); c.mkdirs(parent); }
    if (kind.equals("WRITE")) b.mkdirs(".syncsrv/staging/" + job.id);
    Endpoint sourceEndpoint = job.sourceMode.equals("AGENT") ? null : config.endpoint(job.sourceId);
    Endpoint target = config.endpoint(job.targetId), archive = config.endpoint(job.archiveId);
    boolean direct = source instanceof AgentStorage agent && agent.direct();
    ParallelWork.run(entries, job.archiveThreads, task, () -> {
      // FTP/SFTP and other stateful protocol sessions are never shared between workers.
      Storage a1 = source instanceof AgentStorage agent ? agent.fork() : open.open(sourceEndpoint);
      Storage b1;
      try { b1 = open.open(target); }
      catch (Exception error) { closeOnFailure(a1, error); throw error; }
      Storage c1;
      try { c1 = open.open(archive); }
      catch (Exception error) { closeOnFailure(b1, error); closeOnFailure(a1, error); throw error; }
      return new ParallelWork.Worker<Node>() {
        public void apply(Node entry) throws Exception {
          task.check();
          Operation op = operations.plan(job, kind, entry, current.get(entry.path()), task, direct);
          operations.apply(op, job, a1, b1, c1, current, task);
        }
        public void close() throws Exception {
          try (a1; b1; c1) { /* Close every session even if a preceding close fails. */ }
        }
      };
    });
  }

  private static void closeOnFailure(Storage storage, Exception failure) {
    try { storage.close(); } catch (Exception close) { failure.addSuppressed(close); }
  }
}
