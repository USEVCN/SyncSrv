package com.oadig.syncsrv.modules.transfer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded file workers. All sessions close before the coordinator may commit a snapshot. */
public final class ParallelWork {
  private ParallelWork() {}
  public interface Worker<T> extends AutoCloseable { void apply(T value) throws Exception; }
  @FunctionalInterface public interface Factory<T> { Worker<T> open() throws Exception; }

  public static <T> void run(List<T> entries, int limit, TaskContext task, Factory<T> factory)
      throws Exception {
    if (entries.isEmpty()) return;
    task.check();
    int count = Math.min(Math.max(1, Math.min(16, limit)), entries.size());
    AtomicInteger next = new AtomicInteger(), number = new AtomicInteger();
    AtomicReference<Throwable> failed = new AtomicReference<>();
    ExecutorService pool = Executors.newFixedThreadPool(count, r ->
        new Thread(r, "syncsrv-file-" + task.run.id + "-" + number.incrementAndGet()));
    List<Future<?>> workers = new ArrayList<>();
    // Waiting for workers is wall time, not additional file-stage processing time.
    task.metrics.detach();
    boolean interrupted = false;
    try {
      for (int i = 0; i < count; i++) workers.add(pool.submit(() -> {
        task.metrics.stage("PREPARING");
        try (Worker<T> worker = factory.open()) {
          while (failed.get() == null) {
            task.check();
            int index = next.getAndIncrement();
            if (index >= entries.size()) break;
            try { worker.apply(entries.get(index)); }
            catch (Throwable error) { failed.compareAndSet(null, error); throw error; }
          }
        } catch (Throwable error) {
          failed.compareAndSet(null, error);
        } finally {
          task.metrics.detach();
        }
      }));
      pool.shutdown();
      for (Future<?> worker : workers) worker.get();
    } catch (InterruptedException error) {
      interrupted = true;
      task.cancelled.set(true);
      failed.compareAndSet(null, error);
    } finally {
      if (!pool.isShutdown() || interrupted || task.cancelled.get()) pool.shutdownNow();
      // A paused/failed run must never leave hidden writers behind.
      while (!pool.isTerminated()) {
        try { pool.awaitTermination(1, TimeUnit.SECONDS); }
        catch (InterruptedException error) {
          interrupted = true; task.cancelled.set(true); pool.shutdownNow();
          failed.compareAndSet(null, error);
        }
      }
      task.metrics.stage(task.phase());
      if (interrupted) Thread.currentThread().interrupt();
    }
    Throwable error = failed.get();
    if (error instanceof Exception exception) throw exception;
    if (error instanceof Error fatal) throw fatal;
    task.check();
  }
}
