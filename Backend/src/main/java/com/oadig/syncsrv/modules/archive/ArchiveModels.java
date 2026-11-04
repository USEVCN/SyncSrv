package com.oadig.syncsrv.modules.archive;

import com.oadig.syncsrv.shared.Models.SnapshotSummary;
import java.util.*;

public final class ArchiveModels {
  private ArchiveModels() {}

  public record Request(String beforeMonth, List<String> keepSnapshotIds) {}
  public record Kept(SnapshotSummary snapshot, List<String> reasons) {}
  public record Item(String id, String kind, String endpointId, String location, long size,
      String hash, List<String> versionIds, String snapshotId) {}

  /** Immutable candidate data is signed by Store; progress is updated through audited commits. */
  public static final class Plan {
    public String id = "", jobId = "", beforeMonth = "", cutoff = "", createdAt = "", actor = "",
        state = "READY", fingerprint = "", endpointFingerprint = "", runId = "", error = "",
        completedAt = "", runStatus = "";
    public List<String> keepSnapshotIds = List.of();
    public List<SnapshotSummary> expired = List.of();
    public List<Kept> retained = List.of();
    public List<Item> items = List.of();
    public long estimatedBytes, deletedBytes;
    public int completedFiles, missingFiles, skippedFiles;
    public boolean detailsOmitted;
  }
}
