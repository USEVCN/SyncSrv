package com.oadig.syncsrv.modules.archive;

import com.oadig.syncsrv.modules.archive.ArchiveModels.Item;
import com.oadig.syncsrv.platform.storage.StoragePaths;
import com.oadig.syncsrv.shared.Ids;
import com.oadig.syncsrv.shared.Models.Version;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.io.IOException;
import java.util.Locale;

/** Deletion is limited to registered files under this job's exact archive namespace. */
public final class ArchivePaths {
  private ArchivePaths() {}

  public static String identity(Item item) { return identity(item.endpointId(), item.location()); }
  public static String identity(String endpointId, String location) {
    return endpointId + "\n" + java.text.Normalizer.normalize(location,
        java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
  }
  public static String itemId(String endpointId, String location) {
    return Ids.textHash(identity(endpointId, location));
  }

  static String versionLocation(String jobId, String path, String versionId, String reason) {
    String name = StoragePaths.name(path);
    while (name.getBytes(StandardCharsets.UTF_8).length > 160)
      name = name.substring(0, name.offsetByCodePoints(name.length(), -1));
    String parent = StoragePaths.parent(path);
    return jobId + "/versions/" + (parent.isEmpty() ? "" : parent + "/")
        + name + ".__v" + versionId + ".__" + reason;
  }

  static boolean registered(Item item, Version version) {
    try {
      String jobId = item.location().substring(0, item.location().indexOf('/'));
      validatePath(jobId, item);
      StoragePaths.relative(version.path());
      return "VERSION".equals(item.kind()) && item.versionIds().contains(version.id())
          && jobId.equals(version.jobId()) && item.endpointId().equals(version.endpointId())
          && item.location().equals(version.location()) && item.size() == version.size()
          && item.hash().equals(version.hash()) && !version.path().isBlank()
          && ("deleted".equals(version.reason()) || "replaced".equals(version.reason()))
          && versionLocation(jobId, version.path(), version.id(), version.reason()).equals(version.location());
    } catch (RuntimeException | IOException invalid) { return false; }
  }

  public static void validatePath(String jobId, Item item) throws IOException {
    try {
      if (jobId == null || jobId.isBlank() || jobId.contains("/") || jobId.equals(".syncsrv"))
        throw new IllegalArgumentException();
      StoragePaths.relative(jobId);
      if (item == null || item.endpointId() == null || item.endpointId().isBlank()
          || item.location() == null || item.hash() == null || !item.hash().matches("[a-f0-9]{64}")
          || item.size() < 0 || item.versionIds() == null || item.snapshotId() == null
          || !itemId(item.endpointId(), item.location()).equals(item.id()))
        throw new IllegalArgumentException();
      StoragePaths.relative(item.location());
      if (item.kind().equals("MANIFEST")) {
        if (!item.versionIds().isEmpty() || !item.snapshotId().matches("[a-f0-9]{32}")
            || !item.location().equals(jobId + "/snapshots/" + item.snapshotId() + ".json"))
          throw new IllegalArgumentException();
      } else if (item.kind().equals("VERSION")) {
        if (!item.snapshotId().isEmpty() || item.versionIds().isEmpty()
            || !item.location().startsWith(jobId + "/versions/")
            || new HashSet<>(item.versionIds()).size() != item.versionIds().size()
            || item.versionIds().stream().anyMatch(id -> id == null || !id.matches("[a-f0-9]{32}")))
          throw new IllegalArgumentException();
        String name = StoragePaths.name(item.location());
        if (item.versionIds().stream().noneMatch(id -> name.endsWith(".__v" + id + ".__deleted")
            || name.endsWith(".__v" + id + ".__replaced"))) throw new IllegalArgumentException();
      } else throw new IllegalArgumentException();
    } catch (RuntimeException e) {
      throw new IOException("归档整理路径或文件身份不安全，停止操作", e);
    }
  }
}
