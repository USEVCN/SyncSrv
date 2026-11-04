package com.oadig.syncsrv.modules.transfer;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.Ids;
import com.oadig.syncsrv.shared.Models.Transfer;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** The protected checkpoint read-back also supplies the complete file SHA-256. */
final class VerifiedStorageCopy {
  private final Store store;
  VerifiedStorageCopy(Store store) { this.store = store; }

  boolean copy(Storage source, String from, Storage target, String to, long size,
      String hash, String id, TaskContext task) throws Exception {
    if (size <= 0) return false;
    try (CopySession session = source.openCopySession(from, target, to)) {
      if (session == null) return false;
      if (session.sourceSize() != size) throw new IOException("源文件长度发生变化");
      MessageDigest digest = Ids.digest();
      Transfer old = store.get("transfer", id, Transfer.class);
      Transfer resumed = resume(session, old, size, hash, from, digest, task);
      long offset = resumed == null ? 0 : resumed.confirmed();
      String chain = resumed == null ? "" : resumed.checkpointHash();
      if (offset == 0) { digest.reset(); session.truncate(); }
      task.progress.beginTransfer(offset, task.phase());
      boolean started = false;
      if (offset < size) store.commit(task.run.actor, "transfer.server_copy_requested", task.run.id,
          Map.of("path", from, "target", to, "offset", offset, "size", size));
      while (offset < size) {
        task.check();
        int count = (int) Math.min(TransferService.CHUNK_SIZE, size - offset);
        throttle(count, task);
        long start = System.nanoTime();
        if (!session.copy(offset, count)) {
          if (started) throw new IOException("存储端复制中断，保留已校验断点");
          store.commit(task.run.actor, "transfer.mode", task.run.id,
              Map.of("path", from, "mode", "CENTER_RELAY", "reason", "SERVER_COPY_UNSUPPORTED"));
          task.metrics.note("存储未支持直接复制，此文件通过中心中转；完整校验和断点保留。");
          return false;
        }
        task.metrics.serverCopied(System.nanoTime() - start, count);
        task.progress.transferred(count);
        if (!started) {
          store.commit(task.run.actor, "transfer.mode", task.run.id,
              Map.of("path", from, "target", to, "mode", "SMB_SERVER_COPY",
                  "verification", "LOCKED_CHECKPOINT_SHA256"));
          started = true;
        }
        byte[] block = read(session, offset, count, task);
        digest.update(block);
        String blockHash = Ids.hash(block);
        chain = chain(chain, offset, count, blockHash);
        offset += count;
        store.commit(task.run.actor, "transfer.checkpoint", task.run.id,
            Map.of("path", from, "confirmed", offset, "chunkHash", blockHash, "mode", "SMB_SERVER_COPY"),
            put("transfer", id, new Transfer(id, hash, size, offset, chain)));
      }
      task.check();
      task.metrics.checksum();
      if (session.targetSize() != size || !HexFormat.of().formatHex(digest.digest()).equals(hash))
        throw new IOException("目标完整内容校验失败");
      store.commit(task.run.actor, "transfer.verified", task.run.id,
          Map.of("path", from, "target", to, "sha256", hash, "size", size,
              "verification", "LOCKED_CHECKPOINT_SHA256"), remove("transfer", id));
      task.metrics.note("SMB 复制复用受保护的文件句柄；分块读回同时完成整文件 SHA-256 校验。");
      return true;
    }
  }

  private Transfer resume(CopySession session, Transfer old, long size, String hash,
      String from, MessageDigest digest, TaskContext task) throws Exception {
    if (old == null) return null;
    long length = session.targetSize();
    boolean good = old.confirmed() > 0 && old.confirmed() <= size
        && length >= old.confirmed() && length <= size
        && old.expectedSize() == size && old.expectedHash().equals(hash);
    if (good) {
      String chain = "";
      try (var progress = task.verifying("VERIFYING_RESUME", from, old.confirmed())) {
        for (long offset = 0; offset < old.confirmed();) {
          int count = (int) Math.min(TransferService.CHUNK_SIZE, old.confirmed() - offset);
          byte[] block = read(session, offset, count, task);
          digest.update(block);
          chain = chain(chain, offset, count, Ids.hash(block));
          offset += count;
        }
      }
      good = chain.equals(old.checkpointHash());
      // A complete but corrupt previous attempt must not get stuck retrying its final hash.
      // Keep its checkpoint on failure; discard it only after this fresh protected read proves it bad.
      if (good && old.confirmed() == size)
        good = HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest()).equals(hash);
    }
    if (good) {
      task.metrics.resumed(old.confirmed());
      store.commit(task.run.actor, "transfer.resumed", task.run.id,
          Map.of("path", from, "confirmed", old.confirmed()));
      return old;
    }
    task.metrics.restarted();
    store.commit(task.run.actor, "transfer.restart", task.run.id,
        Map.of("path", from, "reason", "CHECKPOINT_NOT_VERIFIABLE"), remove("transfer", old.id()));
    return null;
  }

  private byte[] read(CopySession session, long offset, int count, TaskContext task) throws Exception {
    byte[] block = new byte[count];
    try (InputStream in = task.paced(session.read(offset, count), false)) {
      if (in.readNBytes(block, 0, count) != count) throw new IOException("存储端复制长度不足");
    }
    return block;
  }

  private static String chain(String previous, long offset, int count, String hash) {
    return Ids.textHash(previous + "\n" + offset + "\n" + count + "\n" + hash);
  }

  private void throttle(int count, TaskContext task) throws Exception {
    long start = System.nanoTime();
    try {
      for (int left = count; left > 0;) {
        int part = Math.min(left, 256 * 1024);
        task.global.acquire(part, task); task.job.acquire(part, task); left -= part;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("存储端复制已暂停");
    } finally { task.metrics.throttle(System.nanoTime() - start); }
  }
}
