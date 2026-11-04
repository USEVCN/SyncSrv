package com.oadig.syncsrv.modules.sync;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.IOException;
import java.util.*;

/** Every destructive step is preceded by a durable intent and a verified historical copy. */
public final class FileOperations {
  private final Store store;
  private final TransferService transfer;

  public FileOperations(Store store, TransferService transfer) {
    this.store = store;
    this.transfer = transfer;
  }

  public Operation plan(Job job, String kind, Node entry, Node old, TaskContext task)
      throws Exception {
    var state = kind.equals("WRITE") && job.sourceMode.equals("AGENT")
        ? com.oadig.syncsrv.modules.discovery.AgentSource.state(store, job.id) : null;
    return plan(job, kind, entry, old, task, state != null && state.direct());
  }

  public Operation plan(Job job, String kind, Node entry, Node old, TaskContext task, boolean direct)
      throws Exception {
    Operation op = new Operation();
    op.id = Ids.next(); op.jobId = job.id; op.kind = kind; op.path = entry.path(); op.entry = entry;
    op.direct = direct;
    op.temp = ".syncsrv/staging/" + job.id + "/" + op.id + ".part";
    if (old != null && !old.directory()) {
      op.oldVersion = ConfigurationLookup.version(store, old.versionId());
      String reason = kind.equals("DELETE") ? "deleted" : "replaced";
      op.archivePath =
          job.id
              + "/versions/"
              + StoragePaths.parent(op.path)
              + "/"
              + archiveName(op.path, op.oldVersion.id(), reason);
      op.archivePath = op.archivePath.replace("//", "/");
    }
    if (kind.equals("WRITE"))
      op.newVersion =
          new Version(
              Ids.next(),
              job.id,
              op.path,
              job.targetId,
              op.path,
              entry.size(),
              entry.hash(),
              Ids.now(),
              "current");
    save(op, task, "operation.planned");
    return op;
  }

  private String archiveName(String path, String version, String reason) {
    String name = StoragePaths.name(path);
    while (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 160)
      name = name.substring(0, name.offsetByCodePoints(name.length(), -1));
    return name + ".__v" + version + ".__" + reason;
  }

  private void save(Operation op, TaskContext task, String action) throws Exception {
    store.commit(task.run.actor, action, task.run.id, op, put("operation", op.id, op));
  }

  /** Only PLANNED writes are disposable: the live B file has not been touched at this phase. */
  public boolean supersedeUnstaged(Operation op, Storage a, Storage b, TaskContext task)
      throws Exception {
    if (!op.kind.equals("WRITE") || !op.phase.equals("PLANNED")) return false;
    task.check();
    Storage.Item item = sourceStat(a, op.path);
    boolean changed =
        item == null
            || item.directory()
            || item.size() != op.entry.size()
            || item.modified() != op.entry.modified();
    if (!changed) {
      String hash =
          a instanceof com.oadig.syncsrv.modules.discovery.AgentStorage agent
              ? agent.hash(op.path)
              : transfer.hash(a, op.path, item.size(), task);
      changed = !hash.equals(op.entry.hash());
    }
    if (!changed) return false;
    // Removal is limited to this operation's private staging path; B and C remain untouched.
    Storage.Item partial = b.stat(op.temp);
    if (partial != null) {
      if (partial.directory() || partial.symbolic()) throw new IOException("暂存路径类型异常");
      b.delete(op.temp, false);
    }
    store.commit(
        task.run.actor,
        "operation.superseded_source_changed",
        task.run.id,
        Map.of(
            "path",
            op.path,
            "operationId",
            op.id,
            "skippedHash",
            op.entry.hash(),
            "skippedSize",
            op.entry.size(),
            "reason",
            "SOURCE_CHANGED_BEFORE_STAGING"),
        remove("operation", op.id),
        remove("transfer", TransferService.checkpointId(op.id, op.path, op.temp, op.entry.hash())));
    return true;
  }

  public void apply(
      Operation op,
      Job job,
      Storage a,
      Storage b,
      Storage c,
      Map<String, Node> current,
      TaskContext task)
      throws Exception {
    task.progress.begin(op.path, op.kind, op.entry.directory() ? 0 : op.entry.size());
    try (var measured = task.metrics.operation(op.path, op.kind)) {
      try {
        applyMeasured(op, job, a, b, c, current, task);
      } catch (Exception e) {
        measured.failed(e, task.cancelled.get());
        task.progress.finishFile(task.cancelled.get() ? "PAUSED" : "FAILED", com.oadig.syncsrv.modules.jobs.TaskManager.safeMessage(e));
        throw e;
      }
    }
  }

  private void applyMeasured(
      Operation op,
      Job job,
      Storage a,
      Storage b,
      Storage c,
      Map<String, Node> current,
      TaskContext task)
      throws Exception {
    task.check();
    task.stage(op.kind.equals("DELETE") ? "ARCHIVING" : "TRANSFERRING", op.path);
    if (op.kind.equals("MKDIR") || op.kind.equals("RMDIR")) {
      Storage.Item exists = b.stat(op.path);
      if (op.kind.equals("MKDIR")) {
        b.mkdirs(op.path);
        current.put(op.path, op.entry);
      } else {
        Storage.Item source = sourceStat(a, op.path);
        if (source != null && source.directory()) {
          cancel(op, task);
          return;
        }
        if (exists != null) {
          if (!exists.directory()) throw new IOException("备份库目录类型异常");
          b.delete(op.path, true);
        }
        current.remove(op.path);
      }
      finish(op, current, task);
      return;
    }
    if (op.kind.equals("DELETE")) {
      Storage.Item source = sourceStat(a, op.path);
      if (source != null && !source.directory()) {
        cancel(op, task);
        return;
      }
    }
    if (op.phase.equals("PLANNED")) {
      if (op.newVersion != null) {
        if (a instanceof com.oadig.syncsrv.modules.discovery.AgentStorage agent && agent.direct()) {
          // An interrupted rename can leave the validated file at the operation path
          // already.
          b.mkdirs(StoragePaths.parent(op.temp));
          if (b.stat(op.temp) == null) agent.stage(op.path, b, op.temp);
          else requireMatch(b, op.temp, op.entry.size(), op.entry.hash(), task);
          store.commit(
              task.run.actor,
              "source.direct_staged",
              task.run.id,
              Map.of("path", op.path, "bytes", op.entry.size()));
        } else transfer.copy(a, op.path, b, op.temp, op.entry.size(), op.entry.hash(), op.id, task);
      }
      op.phase = "STAGED";
      save(op, task, "operation.staged");
    }
    boolean archiveVerifiedHere = false;
    if (op.phase.equals("STAGED")) {
      if (op.oldVersion != null) {
        task.stage("ARCHIVING", op.path);
        Version v = op.oldVersion;
        c.mkdirs(StoragePaths.parent(op.archivePath));
        if (!matches(c, op.archivePath, v.size(), v.hash(), task)) {
          // The archive's complete hash validates these bytes; B is hashed again before deletion.
          // Do not add another full source read immediately before that verified copy.
          Storage.Item archiveSource = b.stat(op.path);
          if (archiveSource == null || archiveSource.directory() || archiveSource.symbolic()
              || archiveSource.size() != v.size())
            throw new IOException("备份库文件类型或长度变化，停止归档: " + op.path);
          transfer.copy(
              b, op.path, c, op.archivePath, v.size(), v.hash(), op.id + "-archive", task);
        }
        archiveVerifiedHere = true;
        Version archived =
            v.archived(
                job.archiveId, op.archivePath, op.kind.equals("DELETE") ? "deleted" : "replaced");
        op.phase = "ARCHIVED";
        store.commit(
            task.run.actor,
            "version.archived",
            task.run.id,
            Map.of("operation", op, "version", archived),
            put("version", v.id(), archived),
            put("operation", op.id, op));
      } else {
        op.phase = "ARCHIVED";
        save(op, task, "operation.ready");
      }
    }
    // Recheck after recovery, but reuse the full verification just completed in this call.
    // This is deliberately local: no cached result survives a failure or process restart.
    if (op.oldVersion != null && !archiveVerifiedHere)
      requireMatch(c, op.archivePath, op.oldVersion.size(), op.oldVersion.hash(), task);
    boolean installed =
        op.newVersion != null
            && (!op.direct || b.stat(op.temp) == null)
            && matches(b, op.path, op.entry.size(), op.entry.hash(), task);
    if (op.newVersion != null && !installed) {
      if (op.direct) requireReceived(b, op.temp, op.entry.size());
      else requireMatch(b, op.temp, op.entry.size(), op.entry.hash(), task);
    }
    Storage.Item existing = b.stat(op.path);
    if (existing != null && !installed) {
      if (op.oldVersion == null) throw new IOException("备份库出现未知文件，禁止覆盖: " + op.path);
      requireMatch(b, op.path, op.oldVersion.size(), op.oldVersion.hash(), task);
      if (op.kind.equals("DELETE")) {
        Storage.Item fresh = sourceStat(a, op.path);
        if (fresh != null && !fresh.directory()) {
          cancel(op, task);
          return;
        }
      }
      store.commit(
          task.run.actor,
          "file.remove_intent",
          task.run.id,
          Map.of("path", op.path, "archivedVersion", op.oldVersion.id()));
      b.delete(op.path, false);
    }
    if (op.newVersion != null) {
      if (!installed) {
        b.mkdirs(StoragePaths.parent(op.path));
        b.move(op.temp, op.path);
      }
      if (op.direct) requireReceived(b, op.path, op.entry.size());
      else requireMatch(b, op.path, op.entry.size(), op.entry.hash(), task);
      current.put(op.path, op.entry.version(op.newVersion.id()));
    } else current.remove(op.path);
    finish(op, current, task);
  }

  private void requireReceived(Storage b, String path, long size) throws Exception {
    Storage.Item item = b.stat(path);
    if (item == null || item.directory() || item.symbolic() || item.size() != size)
      throw new IOException("源端已校验文件在备份库内提交失败: " + path);
  }

  private void cancel(Operation op, TaskContext task) throws Exception {
    task.progress.finishFile("SKIPPED", "源文件状态已变化，此操作已跳过");
    store.commit(
        task.run.actor,
        "operation.cancelled_source_returned",
        task.run.id,
        Map.of("path", op.path),
        remove("operation", op.id));
  }

  private Storage.Item sourceStat(Storage source, String path) throws Exception {
    String current = "";
    String[] segments = path.split("/");
    for (int i = 0; i < segments.length; i++) {
      current = current.isEmpty() ? segments[i] : current + "/" + segments[i];
      Storage.Item item = source.stat(current);
      if (item == null) return null;
      if (item.symbolic()) throw new IOException("源路径出现符号链接，停止删除判断");
      if (i == segments.length - 1) return item;
      // A parent replaced by a regular file proves its former children are absent.
      if (!item.directory()) return null;
    }
    return null;
  }

  private void finish(Operation op, Map<String, Node> current, TaskContext task) throws Exception {
    synchronized (store) {
      List<Store.Change> changes = new ArrayList<>();
      changes.add(CurrentIndex.update(op.jobId, op.path, current.get(op.path)));
      changes.addAll(new DirectoryIndex(store).changes(op.jobId, op.path, current.get(op.path)));
      changes.add(remove("operation", op.id));
      if (op.newVersion != null) changes.add(put("version", op.newVersion.id(), op.newVersion));
      store.commit(
          task.run.actor,
          "operation.committed",
          task.run.id,
          Map.of(
              "path",
              op.path,
              "kind",
              op.kind,
              "version",
              op.newVersion == null ? "" : op.newVersion.id()),
          changes.toArray(Store.Change[]::new));
    }
    task.done();
  }

  public boolean matches(Storage storage, String path, long size, String hash, TaskContext task)
      throws Exception {
    Storage.Item item = storage.stat(path);
    return item != null
        && !item.directory()
        && item.size() == size
        && transfer.hash(storage, path, size, task).equals(hash);
  }

  public void requireMatch(Storage storage, String path, long size, String hash, TaskContext task)
      throws Exception {
    if (!matches(storage, path, size, hash, task)) throw new IOException("内容校验失败，停止操作: " + path);
  }

  private static class ConfigurationLookup {
    static Version version(Store store, String id) throws Exception {
      Version v = store.get("version", id, Version.class);
      if (v == null) throw new IOException("版本记录丢失: " + id);
      return v;
    }
  }
}
