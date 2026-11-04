package com.oadig.syncsrv.modules.restore;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.sync.*;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class RestoreService {
  public record Plan(
      Snapshot snapshot, List<RestoreItem> items, List<String> excludedCurrentPaths) {}

  public record Receipt(
      String runId,
      String snapshotId,
      String manifestHash,
      String destination,
      String completedAt,
      int files,
      long bytes,
      String treeHash) {}

  private final Store store;
  private final ConfigurationService config;
  private final SyncEngine sync;
  private final TransferService transfer;
  private final FileBatch.Open open;

  public RestoreService(
      Store store, ConfigurationService config, SyncEngine sync, TransferService transfer) {
    this(store, config, sync, transfer, endpoint -> StorageFactory.open(endpoint, store));
  }

  public RestoreService(Store store, ConfigurationService config, SyncEngine sync,
      TransferService transfer, FileBatch.Open open) {
    this.open = open;
    this.store = store;
    this.config = config;
    this.sync = sync;
    this.transfer = transfer;
  }

  public Plan plan(String snapshotId) throws Exception {
    Snapshot s = sync.snapshot(snapshotId);
    Job job = config.job(s.jobId());
    List<RestoreItem> items = new ArrayList<>();
    Set<String> paths = new HashSet<>();
    Map<String, Version> versions =
        store.getMany(
            "version",
            s.entries().stream().filter(n -> !n.directory()).map(Node::versionId).toList(),
            Version.class);
    for (Node node : s.entries()) {
      paths.add(node.path());
      if (node.directory()) continue;
      Version v = ConfigurationService.require(versions.get(node.versionId()), "历史版本不存在");
      if (!v.jobId().equals(s.jobId())
          || !v.path().equals(node.path())
          || !v.hash().equals(node.hash())
          || v.size() != node.size()
          || !List.of(job.targetId, job.archiveId).contains(v.endpointId()))
        throw new IOException("版本与恢复清单不一致");
      items.add(
          new RestoreItem(
              node.path(),
              v.id(),
              v.endpointId(),
              v.location(),
              v.hash(),
              v.size(),
              v.endpointId().equals(job.targetId) ? "CURRENT_B" : "HISTORY_C"));
    }
    List<String> exclusions =
        sync.current(s.jobId()).keySet().stream().filter(p -> !paths.contains(p)).sorted().toList();
    return new Plan(s, List.copyOf(items), exclusions);
  }

  public void restore(String snapshotId, String directory, TaskContext task) throws Exception {
    restore(snapshotId, "", directory, task);
  }

  public void restore(String snapshotId, String endpointId, String directory, TaskContext task)
      throws Exception {
    Plan plan = plan(snapshotId);
    try (RestoreDestination target = new RestoreDestination(config, endpointId, directory)) {
      String destination = target.identity;
      String owner = Ids.textHash(snapshotId + "\n" + destination);
      String marker = ".syncsrv/restore-owner";
      Storage.Item existingRoot = target.stat("");
      if (existingRoot != null) {
        if (!existingRoot.directory() || existingRoot.symbolic())
          throw new IOException("恢复目标不是普通目录");
        if (!target.list("").isEmpty()) {
          Storage.Item ownership = target.stat(marker);
          if (ownership == null
              || ownership.directory()
              || ownership.symbolic()
              || ownership.size() != owner.length())
            throw new IOException("恢复目标必须为空目录，或是该恢复节点之前中断的目录");
          try (InputStream in = target.read(marker, 0)) {
            if (!new String(in.readNBytes(129), java.nio.charset.StandardCharsets.UTF_8)
                .equals(owner)) throw new IOException("恢复目标属于其他节点，禁止覆盖");
          }
        }
      }
      target.mkdirs("");
      target.mkdirs(".syncsrv");
      byte[] ownerBytes = owner.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      target.write(
          ".syncsrv/restore-owner", 0, new ByteArrayInputStream(ownerBytes), ownerBytes.length);
      store.commit(
          task.run.actor,
          "restore.plan",
          task.run.id,
          plan,
          put("restore-plan", task.run.id, plan));
      task.run.total = plan.items().size();
      for (RestoreItem item : plan.items()) task.progress.plan(item.path(), "RESTORE", item.size());
      task.progress.planned();
      int limit = config.job(plan.snapshot().jobId()).archiveThreads;
      for (Node node : plan.snapshot().entries().stream().filter(Node::directory)
          .sorted(Comparator.comparingInt(n -> TreeScanner.depth(n.path()))).toList()) {
        task.check(); target.mkdirs(node.path());
      }
      RestoreFiles.copy(plan.items(), target, owner, limit, config, store, transfer, open, task);
      Map<String, Node> actual = new TreeScanner(transfer)
          .scan(target, task, true, true, limit, target::fork);
      Map<String, Node> expected = new TreeMap<>();
      plan.snapshot().entries().forEach(n -> expected.put(n.path(), n));
      if (!actual.keySet().equals(expected.keySet())) throw new IOException("恢复目录包含未知或缺失条目");
      for (Node n : actual.values())
        if (!TreeScanner.sameContent(n, expected.get(n.path())))
          throw new IOException("恢复文件最终校验失败: " + n.path());
      // Re-enumerate after parallel hashes, including empty directories, before issuing a receipt.
      Map<String, Node> second = new TreeScanner(transfer).scan(target, task, true, false);
      if (!actual.keySet().equals(second.keySet())) throw new IOException("最终校验期间恢复目录变化");
      for (Node node : actual.values()) {
        Node after = second.get(node.path());
        if (node.directory() != after.directory() || node.size() != after.size()
            || node.modified() != after.modified())
          throw new IOException("最终校验期间恢复文件变化: " + node.path());
      }
      byte[] metadata=Json.bytes(Map.of("format","syncsrv-content-metadata-v1","entries",plan.snapshot().entries(),
          "contract","文件内容、相对路径、目录和源修改时间；不包含 ACL、xattr、所有者或 VSS 应用快照"));
      target.write(".syncsrv/source-metadata.json",0,new ByteArrayInputStream(metadata),metadata.length);
      int timestamps=0;
      for(Node node:plan.snapshot().entries().stream().sorted(Comparator.comparingInt((Node n)->TreeScanner.depth(n.path())).reversed()).toList())
        if(target.modified(node.path(),node.modified()))timestamps++;
      task.metrics.note("已保存源元数据清单；已恢复修改时间 "+timestamps+" 项。");
      Receipt receipt =
          new Receipt(
              task.run.id,
              snapshotId,
              plan.snapshot().manifestHash(),
              destination,
              Ids.now(),
              plan.items().size(),
              plan.snapshot().totalBytes(),
              Ids.hash(Json.bytes(expected.values())));
      byte[] content = Json.bytes(receipt);
      target.write(".syncsrv/receipt.json", 0, new ByteArrayInputStream(content), content.length);
      store.commit(
          task.run.actor,
          "restore.completed",
          task.run.id,
          receipt,
          put("receipt", task.run.id, receipt));
    }
  }
}
