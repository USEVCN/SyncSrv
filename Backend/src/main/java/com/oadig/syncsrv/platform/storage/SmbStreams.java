package com.oadig.syncsrv.platform.storage;

import com.hierynomus.smbj.share.File;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded SMB read-ahead and four in-flight writes; checkpointing still waits for flush. */
final class SmbStreams {
  static final int BUFFER = 1024 * 1024;
  private static final int WINDOW = 4;

  private record Pending(byte[] data, long offset, Future<Long> future) {}

  static void write(File file, long offset, InputStream input, long length) throws IOException {
    if (offset < 0 || length < 0) throw new IllegalArgumentException("写入范围不正确");
    Deque<Pending> pending = new ArrayDeque<>();
    boolean complete = false;
    try {
      long remaining = length, position = offset;
      while (remaining > 0) {
        interrupted();
        if (pending.size() == WINDOW) finish(file, pending.removeFirst());
        int count = (int) Math.min(BUFFER, remaining);
        byte[] block = new byte[count];
        if (input.readNBytes(block, 0, count) != count) throw new EOFException("SMB 写入数据长度不足");
        pending.addLast(new Pending(block, position, file.writeAsync(block, position, 0, count)));
        position += count;
        remaining -= count;
      }
      while (!pending.isEmpty()) finish(file, pending.removeFirst());
      interrupted();
      file.flush();
      complete = true;
    } finally {
      if (!complete) for (Pending write : pending) write.future().cancel(true);
    }
  }

  private static void finish(File file, Pending pending) throws IOException {
    int sent = 0;
    Future<Long> future = pending.future();
    try {
      while (sent < pending.data().length) {
        interrupted();
        long count = future.get(30, TimeUnit.SECONDS);
        if (count <= 0 || count > pending.data().length - sent)
          throw new IOException("SMB 写入无进展或返回长度异常");
        sent += (int) count;
        if (sent < pending.data().length)
          future =
              file.writeAsync(
                  pending.data(), pending.offset() + sent, sent, pending.data().length - sent);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("SMB 写入已暂停");
    } catch (ExecutionException | TimeoutException | CancellationException e) {
      throw new IOException("SMB 写入失败或超时", e);
    } finally {
      if (!future.isDone()) future.cancel(true);
    }
  }

  private static void interrupted() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("SMB 传输已暂停");
  }
}
