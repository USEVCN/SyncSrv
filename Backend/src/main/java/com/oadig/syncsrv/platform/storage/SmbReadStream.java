package com.oadig.syncsrv.platform.storage;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.mssmb2.messages.*;
import com.hierynomus.smbj.share.File;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Ordered delivery with a bounded window of signed SMB requests. */
final class SmbReadStream extends InputStream {
  @FunctionalInterface
  interface Request {
    Future<SMB2ReadResponse> send(long offset, int length) throws IOException;
  }

  private record Pending(long offset, int length, Future<SMB2ReadResponse> reply) {}

  private final Request request;
  private final Closeable handle;
  private final int blockSize, window;
  private final long end;
  private final Deque<Pending> pending = new ArrayDeque<>();
  private byte[] data = new byte[0];
  private long next;
  private int position;
  private boolean eof, closed;

  static InputStream open(File file, long offset, long length) throws IOException {
    return open(file, offset, length, true);
  }

  static InputStream open(File file, long offset, long length, boolean ownHandle) throws IOException {
    try {
      var tree = file.getDiskShare().getTreeConnect();
      var protocol = tree.getNegotiatedProtocol();
      var session = tree.getSession();
      int block = Math.min(SmbStreams.BUFFER, protocol.getMaxReadSize());
      return new SmbReadStream(
          (at, count) ->
              session.send(
                  new SMB2ReadRequest(
                      protocol.getDialect(),
                      file.getFileId(),
                      session.getSessionId(),
                      tree.getTreeId(),
                      at,
                      count)),
          ownHandle ? file : () -> {},
          offset,
          length,
          block,
          8);
    } catch (RuntimeException e) {
      if (ownHandle) file.close();
      throw e;
    }
  }

  SmbReadStream(
      Request request, Closeable handle, long offset, long length, int blockSize, int window) {
    if (offset < 0 || length < -1 || blockSize <= 0 || window <= 0)
      throw new IllegalArgumentException("SMB 读取范围不正确");
    this.request = request;
    this.handle = handle;
    this.next = offset;
    this.end = length < 0 ? Long.MAX_VALUE : Math.addExact(offset, length);
    this.blockSize = blockSize;
    this.window = window;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, buffer.length);
    if (closed) throw new IOException("SMB 文件已关闭");
    if (length == 0) return 0;
    try {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("SMB 读取已暂停");
      if (position == data.length && !advance()) return -1;
      int count = Math.min(length, data.length - position);
      System.arraycopy(data, position, buffer, offset, count);
      position += count;
      return count;
    } catch (IOException | RuntimeException e) {
      cancel();
      throw e;
    }
  }

  private boolean advance() throws IOException {
    if (eof) return false;
    fill();
    if (pending.isEmpty()) return false;
    // Keep the awaited request in the queue so failures also cancel it.
    Pending head = pending.peekFirst();
    SMB2ReadResponse response;
    try {
      response = head.reply().get(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("SMB 读取已暂停");
    } catch (ExecutionException | TimeoutException | CancellationException e) {
      throw new IOException("SMB 读取失败或超时", e);
    }
    pending.removeFirst();
    long status = response.getHeader().getStatusCode();
    if (status == NtStatus.STATUS_END_OF_FILE.getValue()) {
      eof = true;
      cancel();
      return false;
    }
    if (status != NtStatus.STATUS_SUCCESS.getValue())
      throw new IOException("SMB 读取失败", new SMBApiException(response.getHeader(), "read"));
    data = response.getData();
    position = 0;
    if (data == null || data.length > head.length()) throw new IOException("SMB 返回读取长度异常");
    if (data.length == 0) {
      eof = true;
      cancel();
      return false;
    }
    // Credit negotiation or server short reads can return fewer bytes than requested.
    if (data.length < head.length()) {
      long at = head.offset() + data.length;
      int count = head.length() - data.length;
      pending.addFirst(new Pending(at, count, request.send(at, count)));
    }
    fill();
    return true;
  }

  private void fill() throws IOException {
    while (pending.size() < window && next < end) {
      int count = (int) Math.min(blockSize, end - next);
      pending.addLast(new Pending(next, count, request.send(next, count)));
      next += count;
    }
  }

  // Do not remove SMBJ's protocol registrations: late read replies must still be consumed.
  private void cancel() {
    pending.clear();
  }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      closed = true;
      cancel();
      data = new byte[0];
      handle.close();
    }
  }
}
