package com.oadig.syncsrv.modules.discovery;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.StoragePaths;
import com.oadig.syncsrv.shared.*;
import java.util.*;

/** Durable reception is separate from successful backup. New generations survive an older run. */
public final class ChangeInbox {
  public record Event(
      String id, String jobId, String path, String kind, String observedAt, long coalesced) {
    public Event(String id, String jobId, String path, String kind, String observedAt) {
      this(id, jobId, path, kind, observedAt, 0);
    }
  }

  public record Batch(String epoch, List<Event> events) {}

  public record Receipt(String fingerprint, String receivedAt) {}

  public record Pending(
      String id,
      String jobId,
      String path,
      String generation,
      String firstSeen,
      String updatedAt,
      String reason,
      int merged,
      long sourceCoalesced,
      String error) {}

  public record Claim(String jobId, List<Pending> entries) {
    public List<String> paths() {
      return entries.stream().map(Pending::path).distinct().toList();
    }
  }

  private final Store store;

  public ChangeInbox(Store store) {
    this.store = store;
  }

  private static String kind(String prefix, String id) {
    return Ids.textHash(prefix + ":" + id).substring(0, 32);
  }

  public List<String> receive(String source, Batch batch) throws Exception {
    if (batch.epoch() == null
        || !batch.epoch().matches("[A-Za-z0-9_-]{1,64}")
        || batch.events() == null
        || batch.events().isEmpty()
        || batch.events().size() > 512) throw new IllegalArgumentException("事件批次不正确，最多 512 条");
    synchronized (store) {
      Map<String, Store.Change> changes = new LinkedHashMap<>();
      Map<String, String> seen = new HashMap<>();
      Map<String, Pending> pending = new HashMap<>();
      List<String> ack = new ArrayList<>();
      for (Event event : batch.events()) {
        if (event.id() == null
            || !event.id().matches("[A-Za-z0-9_-]{1,64}")
            || event.jobId() == null
            || event.jobId().isBlank()
            || event.jobId().length() > 64
            || event.kind() == null
            || event.coalesced() < 0
            || !List.of("CHANGE", "RESCAN", "OVERFLOW", "RESTART").contains(event.kind()))
          throw new IllegalArgumentException("事件字段不正确");
        String path = StoragePaths.relative(event.path());
        if (path.length() > 4096
            || path.equalsIgnoreCase(".syncsrv")
            || path.toLowerCase(Locale.ROOT).startsWith(".syncsrv/"))
          throw new IllegalArgumentException("事件路径不受支持");
        if (!event.kind().equals("CHANGE") && !path.isEmpty())
          throw new IllegalArgumentException("覆盖修复事件须指向任务根");
        String receiptId = Ids.textHash(batch.epoch() + ":" + event.id());
        String fingerprint = Ids.hash(Json.bytes(event));
        String receipts = kind("receipts", source);
        Receipt old = store.get(receipts, receiptId, Receipt.class);
        String prior = seen.putIfAbsent(receiptId, fingerprint);
        if ((old != null && !old.fingerprint().equals(fingerprint))
            || (prior != null && !prior.equals(fingerprint)))
          throw new IllegalArgumentException("事件编号不能用于不同内容");
        ack.add(event.id());
        if (old != null || prior != null) continue;
        String now = Ids.now(), id = Ids.textHash(path), scope = kind("pending", event.jobId());
        String key = scope + ":" + id;
        Pending previous =
            pending.containsKey(key) ? pending.get(key) : store.get(scope, id, Pending.class);
        Pending next =
            new Pending(
                id,
                event.jobId(),
                path,
                Ids.next(),
                previous == null ? now : previous.firstSeen(),
                now,
                event.kind(),
                previous == null ? 0 : previous.merged() + 1,
                event.coalesced(),
                "");
        pending.put(key, next);
        changes.put(key, put(scope, id, next));
        changes.put(
            receipts + ":" + receiptId, put(receipts, receiptId, new Receipt(fingerprint, now)));
      }
      if (!changes.isEmpty())
        store.commit(
            source,
            "changes.received",
            source,
            Map.of("epoch", batch.epoch(), "received", ack.size()),
            changes.values().toArray(Store.Change[]::new));
      return ack;
    }
  }

  public List<Pending> pending(String jobId) throws Exception {
    return store.list(kind("pending", jobId), Pending.class).stream()
        .sorted(Comparator.comparing(Pending::firstSeen))
        .toList();
  }

  public Claim claim(String jobId) throws Exception {
    return new Claim(jobId, pending(jobId));
  }

  public void completed(Claim claim, String runId) throws Exception {
    synchronized (store) {
      List<Store.Change> changes = new ArrayList<>();
      for (Pending p : claim.entries()) {
        Pending latest = store.get(kind("pending", p.jobId()), p.id(), Pending.class);
        if (latest != null && latest.generation().equals(p.generation()))
          changes.add(remove(kind("pending", p.jobId()), p.id()));
      }
      if (!changes.isEmpty())
        store.commit(
            "worker",
            "changes.backed_up",
            runId,
            Map.of(
                "jobId",
                claim.jobId(),
                "completedPaths",
                changes.size(),
                "centerMergedNotifications",
                claim.entries().stream().mapToLong(Pending::merged).sum(),
                "sourceCoalescingReports",
                claim.entries().stream().mapToLong(Pending::sourceCoalesced).sum(),
                "details",
                claim.entries().stream()
                    .filter(p -> p.merged() > 0 || p.sourceCoalesced() > 0)
                    .limit(100)
                    .map(
                        p ->
                            Map.of(
                                "path",
                                p.path(),
                                "centerMerged",
                                p.merged(),
                                "sourceReported",
                                p.sourceCoalesced()))
                    .toList(),
                "detailLimit",
                100),
            changes.toArray(Store.Change[]::new));
    }
  }

  public void failed(Claim claim, String error, String runId) throws Exception {
    synchronized (store) {
      List<Store.Change> changes = new ArrayList<>();
      for (Pending p : claim.entries()) {
        Pending latest = store.get(kind("pending", p.jobId()), p.id(), Pending.class);
        if (latest != null && latest.generation().equals(p.generation()))
          changes.add(
              put(
                  kind("pending", p.jobId()),
                  p.id(),
                  new Pending(
                      p.id(),
                      p.jobId(),
                      p.path(),
                      p.generation(),
                      p.firstSeen(),
                      p.updatedAt(),
                      p.reason(),
                      p.merged(),
                      p.sourceCoalesced(),
                      error)));
      }
      if (!changes.isEmpty())
        store.commit(
            "worker",
            "changes.retry_pending",
            runId,
            Map.of("jobId", claim.jobId()),
            changes.toArray(Store.Change[]::new));
    }
  }
}
