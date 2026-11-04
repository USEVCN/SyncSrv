package com.oadig.syncsrv.modules.transfer;

import com.oadig.syncsrv.shared.Ids;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.function.LongSupplier;

/** Bounded, in-memory measurements. Snapshots are persisted with signed run metadata. */
public final class RunMetrics {
  public record Stage(
      String phase,
      long millis,
      long readMillis,
      long writeMillis,
      long throttleMillis,
      long transferBytes,
      long verificationBytes,
      long writtenBytes,
      long readCalls,
      long writeCalls) {}

  public record FileWork(
      String path,
      String kind,
      String status,
      long millis,
      long transferBytes,
      long verificationBytes,
      long writtenBytes,
      String error) {}

  public record Report(
      int schemaVersion,
      String generatedAt,
      long elapsedMillis,
      long workerCpuMillis,
      long peakHeapBytes,
      String lastProgressAt,
      long scannedEntries,
      long resumedBytes,
      int resumeCount,
      int checkpointRestarts,
      long checksumCount,
      long recoveredOperations,
      long cleanupFiles,
      long cleanupBytes,
      long serverCopyBytes,
      long serverCopyMillis,
      List<Stage> stages,
      List<FileWork> slowestOperations,
      List<String> notes) {}

  private static final class Counter {
    long nanos, readNanos, writeNanos, throttleNanos, transferred, verified, written, reads, writes;

    Stage value(String phase, long extra) {
      return new Stage(
          phase,
          (nanos + extra) / 1_000_000,
          readNanos / 1_000_000,
          writeNanos / 1_000_000,
          throttleNanos / 1_000_000,
          transferred,
          verified,
          written,
          reads,
          writes);
    }
  }

  private final LongSupplier clock;
  private final Map<String, Counter> stages = new LinkedHashMap<>();
  private final PriorityQueue<FileWork> slowest =
      new PriorityQueue<>(Comparator.comparingLong(FileWork::millis));
  private final List<String> notes = new ArrayList<>();
  private long started = -1, ended = -1, finishedCpu;
  private boolean cpuAvailable;
  private final Map<Long, Lane> lanes = new HashMap<>();
  private static final class Lane {
    String phase = "PREPARING";
    long entered, cpuStart, nested, transferred, verified, written;
    Operation operation;
  }

  private Lane lane() {
    return lanes.computeIfAbsent(Thread.currentThread().getId(), id -> {
      Lane lane = new Lane();
      lane.entered = clock.getAsLong();
      lane.cpuStart = cpu(id);
      if (lane.cpuStart >= 0) cpuAvailable = true;
      return lane;
    });
  }

  /** Close a worker's timing before its thread exits; CPU counters disappear after exit. */
  public synchronized void detach() {
    Lane lane = lanes.remove(Thread.currentThread().getId());
    if (lane == null) return;
    flush(lane, clock.getAsLong());
    long value = cpu(Thread.currentThread().getId());
    if (value >= 0 && lane.cpuStart >= 0) finishedCpu += Math.max(0, value - lane.cpuStart);
  }

  private void flush(Lane lane, long now) {
    if (started >= 0 && ended < 0)
      stages.computeIfAbsent(lane.phase, key -> new Counter()).nanos += Math.max(0, now - lane.entered);
    lane.entered = now;
  }
  private long peakHeap,
      sampled,
      scanned,
      resumed,
      checksumCount,
      recovered,
      cleanupFiles,
      cleanupBytes;
  private long serverCopyBytes, serverCopyNanos;
  private int resumes, restarts;
  private String lastProgress = "";

  public RunMetrics() {
    this(System::nanoTime);
  }

  public RunMetrics(LongSupplier clock) {
    this.clock = clock;
  }

  public synchronized void start() {
    started = clock.getAsLong();
    lane().entered = started;
    stages.putIfAbsent(lane().phase, new Counter());
    sample(true);
  }

  public synchronized void stage(String next) {
    if (started < 0 || ended >= 0) return;
    long now = clock.getAsLong();
    Lane lane = lane();
    flush(lane, now);
    lane.phase = next == null || next.isBlank() ? "PREPARING" : next;
    stages.computeIfAbsent(lane.phase, ignored -> new Counter());
    sample(false);
  }

  private Counter counter() {
    return stages.computeIfAbsent(lane().phase, ignored -> new Counter());
  }

  public synchronized void read(long nanos, int count, boolean transfer) {
    Counter c = counter();
    c.readNanos += Math.max(0, nanos);
    lane().nested += Math.max(0, nanos);
    c.reads++;
    if (count > 0) {
      if (transfer) { c.transferred += count; lane().transferred += count; }
      else { c.verified += count; lane().verified += count; }
      progress();
    }
  }

  public synchronized void write(long nanos, long count) {
    Counter c = counter();
    c.writeNanos += Math.max(0, nanos);
    c.writes++;
    c.written += count;
    lane().written += count;
    if (count > 0) progress();
  }

  public synchronized void serverCopied(long nanos, long count) {
    serverCopyBytes += count;
    serverCopyNanos += nanos;
    write(nanos, count);
  }

  public synchronized long nestedReadNanos() {
    return lane().nested;
  }

  /** Device I/O is recorded separately from center network reads. */
  public synchronized void deviceWork(long copied, long verified, boolean local) {
    if (copied > 0) {
      counter().written += copied; counter().transferred += copied;
      lane().written += copied; lane().transferred += copied;
    }
    if (verified > 0) { counter().verified += verified; lane().verified += verified; }
    if (copied > 0 || verified > 0) progress();
  }

  public synchronized void throttle(long nanos) {
    counter().throttleNanos += Math.max(0, nanos);
    lane().nested += Math.max(0, nanos);
  }

  public synchronized void scanned() {
    scanned++;
    progress();
  }

  public synchronized void resumed(long bytes) {
    resumed += bytes;
    resumes++;
  }

  public synchronized void restarted() {
    restarts++;
  }

  public synchronized void checksum() {
    checksumCount++;
  }

  public synchronized void recovered() {
    recovered++;
  }

  public synchronized void cleaned(long files, long bytes) {
    cleanupFiles += files;
    cleanupBytes += bytes;
  }

  public synchronized void note(String note) {
    if (notes.size() < 20 && !notes.contains(note)) notes.add(note);
  }

  private void progress() {
    lastProgress = Ids.now();
    sample(false);
  }

  private void sample(boolean force) {
    long now = clock.getAsLong();
    if (!force && now - sampled < 1_000_000_000L) return;
    sampled = now;
    Runtime runtime = Runtime.getRuntime();
    peakHeap = Math.max(peakHeap, runtime.totalMemory() - runtime.freeMemory());
  }

  private long cpu(long threadId) {
    if (threadId <= 0) return -1;
    var bean = ManagementFactory.getThreadMXBean();
    return bean.isThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()
        ? bean.getThreadCpuTime(threadId)
        : -1;
  }

  public synchronized void finish() {
    if (ended >= 0 || started < 0) return;
    long stop = clock.getAsLong();
    for (var entry : lanes.entrySet()) {
      flush(entry.getValue(), stop);
      long cpu = cpu(entry.getKey()), start = entry.getValue().cpuStart;
      if (cpu >= 0 && start >= 0) finishedCpu += Math.max(0, cpu - start);
    }
    ended = stop;
    lanes.clear();
    sample(true);
  }

  public synchronized Report snapshot() {
    long now = clock.getAsLong(), stop = ended < 0 ? now : ended;
    List<Stage> rows = new ArrayList<>();
    stages.forEach(
        (key, value) ->
            rows.add(
                value.value(
                    key,
                    ended < 0 && started >= 0
                        ? lanes.values().stream().filter(l -> key.equals(l.phase))
                            .mapToLong(l -> Math.max(0, now - l.entered)).sum()
                        : 0)));
    long cpu = finishedCpu;
    if (ended < 0) for (var entry : lanes.entrySet()) {
      long value = cpu(entry.getKey()), start = entry.getValue().cpuStart;
      if (value >= 0 && start >= 0) cpu += Math.max(0, value - start);
    }
    List<FileWork> operations = new ArrayList<>(slowest);
    for (Lane lane : lanes.values())
      if (lane.operation != null) operations.add(lane.operation.value("RUNNING"));
    return new Report(
        2,
        Ids.now(),
        started < 0 ? 0 : Math.max(0, stop - started) / 1_000_000,
        started < 0 || !cpuAvailable ? -1 : cpu / 1_000_000,
        peakHeap,
        lastProgress,
        scanned,
        resumed,
        resumes,
        restarts,
        checksumCount,
        recovered,
        cleanupFiles,
        cleanupBytes,
        serverCopyBytes,
        serverCopyNanos / 1_000_000,
        rows,
        operations.stream()
            .sorted(Comparator.comparingLong(FileWork::millis).reversed())
            .limit(20)
            .toList(),
        List.copyOf(notes));
  }

  public synchronized Operation operation(String path, String kind) {
    Lane lane = lane();
    lane.operation = new Operation(path, kind, clock.getAsLong(), lane);
    return lane.operation;
  }

  public final class Operation implements AutoCloseable {
    private final String path, kind;
    private final long begin;
    private final long[] before;
    private final Lane lane;
    private String status = "COMPLETED", error = "";

    private Operation(String path, String kind, long begin, Lane lane) {
      this.path = path;
      this.kind = kind;
      this.begin = begin;
      this.lane = lane;
      this.before = new long[] {lane.transferred, lane.verified, lane.written};
    }

    public void failed(Exception e, boolean paused) {
      status = paused ? "PAUSED" : "FAILED";
      String message = e.getMessage();
      error =
          message == null
              ? e.getClass().getSimpleName()
              : message.substring(0, Math.min(600, message.length()));
    }

    @Override
    public void close() {
      synchronized (RunMetrics.this) {
        slowest.add(value(status));
        if (slowest.size() > 20) slowest.poll();
        lane.operation = null;
      }
    }

    private FileWork value(String state) {
      long[] after = {lane.transferred, lane.verified, lane.written};
      return new FileWork(
          path,
          kind,
          state,
          Math.max(0, clock.getAsLong() - begin) / 1_000_000,
          after[0] - before[0],
          after[1] - before[1],
          after[2] - before[2],
          error);
    }
  }
}
