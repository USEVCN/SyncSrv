package com.oadig.syncsrv.platform.storage;

import java.io.*;
import java.util.Objects;
import net.schmizz.sshj.sftp.RemoteFile;

/** SSHJ owns protocol ordering; these bounds also cover failure cleanup and partial verification. */
final class SftpStreams {
  static final int WINDOW = 8, BLOCK = 64 * 1024;
  private SftpStreams() {}

  static InputStream read(RemoteFile file, long offset, long length) {
    // SSHJ permits maxUnconfirmed + 1 requests. Its length is a read-ahead hint, not EOF.
    InputStream ahead = file.new ReadAheadRemoteFileInputStream(WINDOW - 1, offset, length);
    return new InputStream() {
      private long remaining = length, position = offset;
      private boolean closed;
      public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
      }
      public int read(byte[] bytes, int from, int count) throws IOException {
        Objects.checkFromIndexSize(from, count, bytes.length);
        if (closed) throw new IOException("SFTP 文件已关闭");
        if (count == 0) return 0;
        if (remaining == 0) return -1;
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("SFTP 读取已暂停");
        int request = Math.min(BLOCK, count);
        if (remaining > 0) request = (int)Math.min(request, remaining);
        // SSHJ 0.40 may refill one request beyond its read-ahead hint. Stop using
        // that stream before its full window reaches the end; tail requests are exact.
        int actual = remaining >= 0 && remaining <= (long)WINDOW * BLOCK
            ? file.read(position, bytes, from, request) : ahead.read(bytes, from, request);
        if (actual > 0) {
          position += actual;
          if (remaining > 0) remaining -= actual;
        }
        return actual;
      }
      public void close() throws IOException {
        if (!closed) { closed = true; file.close(); }
      }
    };
  }

  static void write(RemoteFile file, long offset, InputStream input, long length) throws IOException {
    OutputStream output = file.new RemoteFileOutputStream(offset, WINDOW - 1);
    try {
      byte[] buffer = new byte[BLOCK];
      long remaining = length;
      while (remaining > 0) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("SFTP 写入已暂停");
        int count = input.read(buffer, 0, (int)Math.min(BLOCK, remaining));
        if (count < 0) throw new EOFException("SFTP 写入数据不足");
        if (count == 0) continue;
        output.write(buffer, 0, count);
        remaining -= count;
      }
      drain(output);
    } catch (IOException | RuntimeException failure) {
      // flush removes one ACK before throwing. Drain every remaining response, not only the first error.
      boolean interrupted = Thread.interrupted();
      try { drain(output); }
      catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      finally { if (interrupted) Thread.currentThread().interrupt(); }
      throw failure;
    }
  }

  static void drain(OutputStream output) throws IOException {
    IOException failure = null;
    for (int i = 0; i <= WINDOW; i++) {
      try { output.flush(); break; }
      catch (IOException error) {
        if (failure == null) failure = error; else failure.addSuppressed(error);
      }
    }
    if (failure != null) throw failure;
  }
}
