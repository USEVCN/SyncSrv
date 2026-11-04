package com.oadig.syncsrv.modules.transfer;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public final class TransferService {
  public static final int CHUNK_SIZE = 8 * 1024 * 1024;
  private final Store store;

  public TransferService(Store store) {
    this.store = store;
  }

  public static String checkpointId(String identity, String from, String to, String hash) {
    return Ids.textHash(identity + "\n" + from + "\n" + to + "\n" + hash);
  }

  public String hash(Storage storage, String path, long size, TaskContext task) throws Exception {
    String local = new com.oadig.syncsrv.modules.discovery.DeviceExecution(store)
        .hash(storage.location(path), size, task);
    if (local != null) return local;
    MessageDigest hash = Ids.digest();
    try (var progress = task.verifying("HASHING", path, size);
        InputStream in = task.paced(storage.read(path, 0, size == Long.MAX_VALUE ? -1 : size + 1), false)) {
      byte[] b = new byte[256 * 1024];
      int n;
      while ((n = in.read(b)) != -1) hash.update(b, 0, n);
    }
    task.metrics.checksum();
    return HexFormat.of().formatHex(hash.digest());
  }

  public void copy(
      Storage source,
      String from,
      Storage target,
      String to,
      long size,
      String hash,
      String identity,
      TaskContext task)
      throws Exception {
    task.check();
    target.mkdirs(StoragePaths.parent(to));
    String id = checkpointId(identity, from, to, hash);
    task.progress.beginTransfer(0, task.phase());
    if (new com.oadig.syncsrv.modules.discovery.DeviceExecution(store)
        .copy(source.location(from), target.location(to), size, hash, task)) {
      store.commit(task.run.actor, "transfer.device_verified", task.run.id,
          Map.of("path", from, "target", to, "sha256", hash, "size", size), remove("transfer", id));
      return;
    }
    if (new VerifiedStorageCopy(store).copy(source, from, target, to, size, hash, id, task)) return;
    Transfer old = store.get("transfer", id, Transfer.class);
    long offset = 0;
    String checkpointHash = "";
    if (old != null && target.capabilities().offsetWrite() && source.capabilities().offsetRead()) {
      Storage.Item item = target.stat(to);
      boolean good =
          item != null
              && !item.directory()
              && item.size() >= old.confirmed()
              && item.size() <= size
              && old.confirmed() > 0
              && old.confirmed() <= size
              && old.expectedSize() == size
              && old.expectedHash().equals(hash);
      if (good) {
        try (var progress = task.verifying("VERIFYING_RESUME", from, old.confirmed());
            InputStream in = task.paced(target.read(to, 0, old.confirmed()), false)) {
          long pos = 0;
          String chain = "";
          while (pos < old.confirmed()) {
            task.check();
            int length = (int) Math.min(CHUNK_SIZE, old.confirmed() - pos);
            byte[] data = readChunk(in, length);
            if (data.length != length) {
              good = false;
              break;
            }
            chain = chunkChain(chain, pos, length, Ids.hash(data));
            pos += length;
          }
          good = good && pos == old.confirmed() && chain.equals(old.checkpointHash());
        }
      }
      if (good) {
        offset = old.confirmed();
        task.metrics.resumed(offset);
        checkpointHash = old.checkpointHash();
        store.commit(
            task.run.actor,
            "transfer.resumed",
            task.run.id,
            Map.of("path", from, "confirmed", offset));
      } else {
        task.metrics.restarted();
        store.commit(
            task.run.actor,
            "transfer.restart",
            task.run.id,
            Map.of("path", from, "reason", "CHECKPOINT_NOT_VERIFIABLE"),
            remove("transfer", id));
      }
    }
    task.progress.beginTransfer(offset, task.phase());
    boolean serverCopied = new StorageCopy(store).copy(source, from, target, to, size, hash,
        id, offset, checkpointHash, task);
    if(!serverCopied && size>0) {
      for(Storage.Location location:java.util.Arrays.asList(source.location(from),target.location(to)))
        if(location!=null&&!location.endpoint().allowRelay&&!"LOCAL".equals(location.endpoint().protocol))
          throw new IOException("此连接禁止中心中转；请启用存储端复制或绑定可用的备份设备");
      store.commit(task.run.actor,"transfer.mode",task.run.id,Map.of("path",from,"target",to,"mode","CENTER_RELAY"));
      task.metrics.note("此文件通过中心中转；存储端或设备未提供此路径的直接复制能力。");
    }
    if (serverCopied) {
      // Common full-content verification below remains mandatory.
    } else if (!target.capabilities().offsetWrite()) {
      store.commit(
          task.run.actor,
          "transfer.full_upload",
          task.run.id,
          Map.of("path", from, "reason", "TARGET_HAS_NO_RESUMABLE_UPLOAD"));
      try (InputStream in = task.paced(source.read(from, 0), true)) {
        task.write(target, to, 0, in, size);
      }
    } else {
      if (size == 0) {
        task.write(target, to, 0, new ByteArrayInputStream(new byte[0]), 0);
      } else if (offset < size) {
        try (InputStream in = task.paced(source.read(from, offset), true)) {
          while (offset < size) {
            task.check();
            int requested = (int) Math.min(CHUNK_SIZE, size - offset);
            byte[] data = readChunk(in, requested);
            if (data.length != requested) throw new IOException("源文件长度发生变化");
            task.write(target, to, offset, new ByteArrayInputStream(data), data.length);
            String blockHash = Ids.hash(data);
            try (InputStream check = task.paced(target.read(to, offset, data.length), false)) {
              byte[] actual = readChunk(check, data.length);
              if (actual.length != data.length || !Ids.hash(actual).equals(blockHash))
                throw new IOException("目标不支持正确的偏移写入或传输校验失败");
            }
            checkpointHash = chunkChain(checkpointHash, offset, data.length, blockHash);
            offset += data.length;
            Transfer checkpoint = new Transfer(id, hash, size, offset, checkpointHash);
            store.commit(
                task.run.actor,
                "transfer.checkpoint",
                task.run.id,
                Map.of("path", from, "confirmed", offset, "chunkHash", blockHash),
                put("transfer", id, checkpoint));
          }
          if (in.read() != -1) throw new IOException("源文件传输期间增长");
        }
      }
    }
    Storage.Item actual = target.stat(to);
    if (actual == null
        || actual.directory()
        || actual.size() != size
        || !hash(target, to, size, task).equals(hash)) throw new IOException("目标完整内容校验失败");
    store.commit(
        task.run.actor,
        "transfer.verified",
        task.run.id,
        Map.of("path", from, "target", to, "sha256", hash, "size", size),
        remove("transfer", id));
  }

  private static byte[] readChunk(InputStream in, int length) throws IOException {
    // readNBytes(int) splits reads into 8 KiB pieces on Java 17, each an SMB round trip.
    // The buffer overload lets TaskContext issue bounded 256 KiB reads instead.
    byte[] data = new byte[length];
    int actual = in.readNBytes(data, 0, length);
    return actual == length ? data : Arrays.copyOf(data, actual);
  }

  private String chunkChain(String previous, long offset, int size, String hash) {
    return Ids.textHash(previous + "\n" + offset + "\n" + size + "\n" + hash);
  }
}
