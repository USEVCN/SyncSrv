package com.oadig.syncsrv.modules.transfer;

import com.oadig.syncsrv.shared.Models.Run;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TaskContext {
  public final Run run;
  public final RunMetrics metrics = new RunMetrics();
  public final RunProgress progress = new RunProgress();
  public final BandwidthLimiter global, job;
  public final AtomicBoolean cancelled = new AtomicBoolean();
  public volatile Thread thread;
  private final ThreadLocal<Phase> phases = new ThreadLocal<>();
  private record Phase(String name, String path, long bytes, long total) {}
  private Phase local() {
    Phase value = phases.get();
    if (value == null) { value = new Phase(run.phase, run.currentPath, 0, 0); phases.set(value); }
    return value;
  }
  public String phase() { return local().name(); }
  private void publish(Phase value) {
    phases.set(value);
    run.phase = value.name(); run.currentPath = value.path();
    run.phaseBytes = value.bytes(); run.phaseTotal = value.total();
  }

  public TaskContext(Run run, BandwidthLimiter global, double rate) {
    this.run = run;
    this.global = global;
    job = new BandwidthLimiter(rate);
  }

  public void check() throws InterruptedIOException {
    if (cancelled.get() || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("任务已暂停");
  }

  public void stage(String phase, String path) {
    synchronized (run) {
      metrics.stage(phase);
      progress.stage(phase);
      progress.phaseProgress(0, 0);
      publish(new Phase(phase, path, 0, 0));
    }
  }

  /** Scoped progress keeps content verification distinct from newly transferred bytes. */
  public Verification verifying(String phase, String path, long total) {
    synchronized (run) {
      Phase value = local();
      Verification previous = new Verification(value.name(), value.path(), value.bytes(), value.total());
      stage(phase, path);
      publish(new Phase(phase, path, 0, total));
      progress.phaseProgress(0, total);
      return previous;
    }
  }

  public final class Verification implements AutoCloseable {
    private final String phase, path;
    private final long bytes, total;

    private Verification(String phase, String path, long bytes, long total) {
      this.phase = phase;
      this.path = path;
      this.bytes = bytes;
      this.total = total;
    }

    @Override
    public void close() {
      synchronized (run) {
        metrics.stage(phase);
        progress.stage(phase);
        progress.phaseProgress(bytes, total);
        publish(new Phase(phase, path, bytes, total));
      }
    }
  }

  public void bytes(int count) {
    synchronized (run) {
      run.bytes += count;
    }
  }

  public void verified(long count) {
    synchronized (run) {
      run.verifiedBytes += count;
      progress.verified(count);
      Phase value = local();
      if (value.total() > 0)
        publish(new Phase(value.name(), value.path(), value.bytes() + count, value.total()));
    }
  }

  public void done() {
    synchronized (run) {
      run.done++;
      progress.finishFile("COMPLETED", "");
    }
  }

  public void write(
      com.oadig.syncsrv.platform.storage.Storage target,
      String path,
      long offset,
      InputStream input,
      long length)
      throws Exception {
    check();
    long start = System.nanoTime(), nested = metrics.nestedReadNanos();
    boolean success = false;
    try {
      InputStream measured = new FilterInputStream(input) {
        @Override public int read(byte[] b, int off, int len) throws IOException {
          int n=in.read(b,off,len);progress.transferred(n);return n;
        }
        @Override public int read() throws IOException {
          int n=in.read();if(n>=0)progress.transferred(1);return n;
        }
      };
      target.write(path, offset, measured, length);
      success = true;
    } finally {
      metrics.write(
          System.nanoTime() - start - (metrics.nestedReadNanos() - nested), success ? length : 0);
    }
  }

  public InputStream paced(InputStream in, boolean count) {
    return new FilterInputStream(in) {
      @Override
      public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 255;
      }

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        check();
        long readStart = System.nanoTime();
        int n;
        try {
          n = super.read(buffer, offset, Math.min(length, 256 * 1024));
        } catch (IOException | RuntimeException e) {
          metrics.read(System.nanoTime() - readStart, 0, count);
          throw e;
        }
        metrics.read(System.nanoTime() - readStart, n, count);
        if (n > 0) {
          long throttleStart = System.nanoTime();
          try {
            global.acquire(n, TaskContext.this);
            job.acquire(n, TaskContext.this);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("任务已暂停");
          } catch (IOException e) {
            throw e;
          } catch (Exception e) {
            throw new IOException(e);
          } finally {
            metrics.throttle(System.nanoTime() - throttleStart);
          }
          synchronized (run) {
            if (count) run.bytes += n;
            else {
              verified(n);
            }
          }
        }
        return n;
      }
    };
  }
}
