package com.oadig.syncsrv.modules.transfer;

/** A bounded token bucket; rates are processing bytes, shared across reads and verification. */
public final class BandwidthLimiter {
  private double rate, tokens = 256 * 1024;
  private long updated = System.nanoTime();

  public BandwidthLimiter(double mib) {
    set(mib);
  }

  public synchronized void set(double mib) {
    if (!Double.isFinite(mib) || mib < 0 || mib > 100000)
      throw new IllegalArgumentException("限速须为0至100000 MiB/s");
    rate = mib * 1024 * 1024;
    tokens = 256 * 1024;
    updated = System.nanoTime();
  }

  public synchronized double mib() {
    return rate / (1024 * 1024);
  }

  public synchronized long reserve(int count) {
    if (count <= 0 || count > 8*1024*1024) throw new IllegalArgumentException("Invalid bandwidth reservation");
    if (rate == 0) return 0;
    long now = System.nanoTime();
    tokens = Math.min(256*1024, tokens + (now-updated)*rate/1e9);
    updated = now;
    tokens -= count;
    return Math.max(0,(long)Math.ceil(-tokens*1000/rate));
  }

  public void acquire(int count, TaskContext task) throws Exception {
    long remaining = reserve(count);
    while (remaining > 0) {
      task.check();
      long sleep = Math.min(100,remaining);
      Thread.sleep(sleep); remaining -= sleep;
    }
    task.check();
  }
}
