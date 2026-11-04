package com.oadig.syncsrv.modules.transfer;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.Storage;
import com.oadig.syncsrv.shared.Ids;
import com.oadig.syncsrv.shared.Models.Transfer;
import java.io.*;
import java.util.Map;

/** Storage-side data movement with the same verified checkpoints as a relayed copy. */
final class StorageCopy {
  private final Store store;

  StorageCopy(Store store) {
    this.store = store;
  }

  boolean copy(
      Storage source,
      String from,
      Storage target,
      String to,
      long size,
      String hash,
      String id,
      long offset,
      String chain,
      TaskContext task)
      throws Exception {
    if (size == 0 || offset == size || !source.canCopyTo(target)) return false;
    boolean started = false;
    while (offset < size) {
      task.check();
      int count = (int) Math.min(TransferService.CHUNK_SIZE, size - offset);
      long throttle = System.nanoTime();
      try {
        for (int left = count; left > 0; left -= Math.min(left, 256 * 1024)) {
          int part = Math.min(left, 256 * 1024);
          task.global.acquire(part, task);
          task.job.acquire(part, task);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("存储端复制已暂停");
      } finally {
        task.metrics.throttle(System.nanoTime() - throttle);
      }
      if (!started)
        store.commit(
            task.run.actor,
            "transfer.server_copy_requested",
            task.run.id,
            Map.of("path", from, "target", to, "offset", offset, "size", size));
      long start = System.nanoTime();
      boolean copied = source.copyRangeTo(from, target, to, offset, count);
      if (!copied) {
        if (started) throw new IOException("存储端复制中断，保留已校验断点");
        store.commit(
            task.run.actor,
            "transfer.mode",
            task.run.id,
            Map.of("path", from, "mode", "CENTER_RELAY", "reason", "SERVER_COPY_UNSUPPORTED"));
        task.metrics.note("存储未支持直接复制，此文件通过中心中转；完整校验和断点保留。");
        return false;
      }
      if (!started) {
        store.commit(
            task.run.actor,
            "transfer.mode",
            task.run.id,
            Map.of("path", from, "target", to, "mode", "SMB_SERVER_COPY"));
        started = true;
      }
      task.metrics.serverCopied(System.nanoTime() - start, count);
      task.progress.transferred(count);
      byte[] block = new byte[count];
      try (InputStream check = task.paced(target.read(to, offset, count), false)) {
        if (check.readNBytes(block, 0, count) != count) throw new IOException("存储端复制长度不足");
      }
      String blockHash = Ids.hash(block);
      chain = Ids.textHash(chain + "\n" + offset + "\n" + count + "\n" + blockHash);
      offset += count;
      store.commit(
          task.run.actor,
          "transfer.checkpoint",
          task.run.id,
          Map.of(
              "path", from, "confirmed", offset, "chunkHash", blockHash, "mode", "SMB_SERVER_COPY"),
          put("transfer", id, new Transfer(id, hash, size, offset, chain)));
    }
    Storage.Item current = source.stat(from);
    if (current == null || current.directory() || current.size() != size)
      throw new IOException("源文件传输期间长度发生变化");
    return true;
  }
}
