package com.oadig.syncsrv.shared;

import java.util.*;

public final class Models {
  private Models() {}

  public static class Endpoint {
    public String id = "",
        name = "",
        protocol = "LOCAL",
        host = "",
        root = "",
        username = "",
        password = "",
        domain = "",
        hostKey = "";
    public int port;
    public String agentId = "", agentRoot = "";
    public boolean allowRelay = true;
    public String createdAt = "";

    public Endpoint safe() {
      Endpoint e = Json.read(Json.write(this), Endpoint.class);
      e.password = "";
      return e;
    }
  }

  public static class Job {
    public String id = "", name = "", sourceId = "", targetId = "", archiveId = "", createdAt = "";
    public int intervalSeconds = 300;
    public String discoveryMode = "SCAN";
    public String sourceMode = "CONNECTION";
    public String agentId = "", agentRoot = "";
    public int reconcileSeconds = 86400;
    public double bandwidthMiB;
    public int transferThreads = 4;
    public int archiveThreads = 4;
    public boolean adaptiveTransfers = true;
    public int maxTransferThreads = 16;
    public boolean enabled;
  }

  public record Node(
      String path, boolean directory, long size, long modified, String hash, String versionId) {
    public Node version(String id) {
      return new Node(path, directory, size, modified, hash, id);
    }
  }

  public record Version(
      String id,
      String jobId,
      String path,
      String endpointId,
      String location,
      long size,
      String hash,
      String createdAt,
      String reason) {
    public Version archived(String endpoint, String location, String reason) {
      return new Version(id, jobId, path, endpoint, location, size, hash, createdAt, reason);
    }
  }

  public record Snapshot(
      String id,
      String jobId,
      int sequence,
      String startedAt,
      String completedAt,
      List<Node> entries,
      String manifestHash,
      long totalBytes) {}

  public record SnapshotSummary(
      String id,
      String jobId,
      int sequence,
      String startedAt,
      String completedAt,
      int entries,
      long files,
      long totalBytes,
      String manifestHash) {
    public static SnapshotSummary from(Snapshot s) {
      return new SnapshotSummary(
          s.id(),
          s.jobId(),
          s.sequence(),
          s.startedAt(),
          s.completedAt(),
          s.entries().size(),
          s.entries().stream().filter(n -> !n.directory()).count(),
          s.totalBytes(),
          s.manifestHash());
    }
  }

  public static class Run {
    public String id = "",
        jobId = "",
        kind = "SYNC",
        snapshotId = "",
        target = "",
        restoreEndpointId = "",
        actor = "",
        status = "QUEUED",
        phase = "",
        currentPath = "",
        queuedAt = "",
        actualStartedAt = "",
        discoveryMode = "FULL",
        trigger = "MANUAL",
        startedAt = "",
        completedAt = "",
        error = "";
    public String executionMode = "", executionAgentId = "", executionNote = "";
    public long bytes, verifiedBytes, phaseBytes, phaseTotal;
    public int done, total;
    public com.oadig.syncsrv.modules.discovery.SourceTransferProgress.Stats sourceTransfer;
    public com.oadig.syncsrv.modules.transfer.RunProgress.Summary progress;
  }

  public static class Operation {
    public String id = "",
        jobId = "",
        kind = "",
        path = "",
        phase = "PLANNED",
        temp = "",
        archivePath = "";
    public boolean direct;
    public Node entry;
    public Version oldVersion, newVersion;
  }

  public record Transfer(
      String id, String expectedHash, long expectedSize, long confirmed, String checkpointHash) {}

  public record RestoreItem(
      String path,
      String versionId,
      String endpointId,
      String location,
      String hash,
      long size,
      String decision) {}

  public record AuditEvent(
      long sequence,
      String time,
      String actor,
      String action,
      String subject,
      String detail,
      String previous,
      String hash,
      String signature) {}

  public record AuditHead(long sequence, String head, String publicKey, String protection) {}

  public record EvidenceHeader(String format, AuditHead head, String signature) {}
}
