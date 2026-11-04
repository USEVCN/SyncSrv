package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.archive.*;
import com.oadig.syncsrv.modules.archive.ArchiveModels.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.DeviceExecution;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ArchivePlannerTest {
  @TempDir Path root;
  MemoryStore memory;
  ConfigurationService config;
  ArchivePlanner planner;
  Job job;
  Version old, middle, latest;
  Snapshot first, baseline, newest;

  @BeforeEach void setup() throws Exception {
    memory = new MemoryStore(); config = mock(ConfigurationService.class);
    job = new Job(); job.id = "job"; job.sourceId = "A"; job.targetId = "B"; job.archiveId = "C";
    when(config.job(job.id)).thenReturn(job);
    for (String id : List.of("A", "B", "C")) {
      Endpoint endpoint = new Endpoint(); endpoint.id = id;
      endpoint.root = Files.createDirectory(root.resolve(id)).toString();
      when(config.endpoint(id)).thenReturn(endpoint);
    }
    old = version(1, "document.txt", "old", false);
    middle = version(2, "document.txt", "middle", false);
    latest = version(3, "document.txt", "latest", true);
    first = snapshot(11, "2024-01-01T00:00:00Z", old);
    baseline = snapshot(12, "2024-02-20T00:00:00Z", middle);
    newest = snapshot(13, "2024-03-10T00:00:00Z", latest);
    memory.put("sync-checkpoint", job.id, new SyncEngine.Checkpoint(newest.completedAt(), newest.id(), 1));
    memory.put("current", job.id, new Node[] {node(latest)});
    planner = new ArchivePlanner(memory.store, config);
  }

  @Test void monthRetainsBoundaryAndLatestAndEstimatesOnlyUniquePhysicalFiles() throws Exception {
    Plan p = plan();
    assertEquals("2024-02-29T16:00:00Z", p.cutoff);
    assertEquals(List.of(first.id()), p.expired.stream().map(SnapshotSummary::id).toList());
    assertEquals(2, p.retained.size());
    assertTrue(p.retained.stream().filter(k -> k.snapshot().id().equals(baseline.id()))
        .anyMatch(k -> k.reasons().contains("截止时间之前的基准恢复点")));
    assertEquals(List.of(old.id()), versions(p).stream().flatMap(i -> i.versionIds().stream()).toList());
    Item manifest = p.items.stream().filter(i -> i.kind().equals("MANIFEST")).findFirst().orElseThrow();
    assertEquals(Ids.hash(Json.bytes(first)), manifest.hash());
    assertEquals(Json.bytes(first).length, manifest.size());
    assertEquals(old.size() + Json.bytes(first).length, p.estimatedBytes);
    assertTrue(memory.actions.isEmpty());
    assertNull(memory.store.get("current-index", job.id, Object.class));
    try (var paths = Files.walk(root.resolve("C"))) { assertEquals(1, paths.count()); }
  }

  @Test void exactCutoffIsKeptAndTheLastPointBeforeItIsBaseline() throws Exception {
    replaceTime(baseline, "2024-02-29T15:59:59Z");
    replaceTime(newest, "2024-02-29T16:00:00Z");
    Plan p = plan();
    assertEquals(1, p.expired.size());
    assertTrue(p.retained.stream().filter(k -> k.snapshot().id().equals(newest.id()))
        .anyMatch(k -> k.reasons().contains("指定月份及之后的恢复点")));
  }

  @Test void sharedVersionAcrossExpiredAndRetainedSnapshotsCannotBeReclaimed() throws Exception {
    Version shared = version(4, "shared.txt", "shared", false);
    snapshot(11, first.completedAt(), old, shared);
    snapshot(12, baseline.completedAt(), middle, shared);
    Plan p = plan();
    assertEquals(List.of(old.id()), versions(p).stream().flatMap(i -> i.versionIds().stream()).toList());
  }

  @Test void oldContentCreationTimeDoesNotExpireRecentArchiveReferences() throws Exception {
    assertTrue(middle.createdAt().startsWith("2000"));
    assertFalse(versions(plan()).stream().anyMatch(i -> i.versionIds().contains(middle.id())));
  }

  @Test void currentIndexProtectsVersionEvenWhenItsOnlySnapshotExpires() throws Exception {
    memory.put("current", job.id, new Node[] {node(old)});
    Plan p = plan();
    assertEquals(1, p.expired.size());
    assertTrue(versions(p).isEmpty());
  }

  @Test void checkpointAndExplicitKeepProtectOldRestorePoints() throws Exception {
    memory.put("sync-checkpoint", job.id, new SyncEngine.Checkpoint(first.completedAt(), first.id(), 1));
    assertTrue(plan().expired.isEmpty());
    memory.put("sync-checkpoint", job.id, new SyncEngine.Checkpoint(newest.completedAt(), newest.id(), 1));
    Plan explicit = planner.plan(job.id, new Request("2024-03", List.of(first.id())));
    assertTrue(explicit.expired.isEmpty());
    assertThrows(Exception.class, () -> planner.plan(job.id, new Request("2024-03", List.of(id(999)))));
  }

  @Test void pausedFailedAndInterruptedRestorePlansPinTheirSnapshot() throws Exception {
    for (String status : List.of("QUEUED", "RUNNING", "PAUSED", "FAILED", "INTERRUPTED")) {
      addRestore(status);
      assertTrue(plan().expired.isEmpty(), status);
    }
    addRestore("COMPLETED");
    assertEquals(1, plan().expired.size());
  }

  @Test void orphanRestorePlanRemainsProtectedWithoutClaimingSuccessfulCompletion() throws Exception {
    memory.put("restore-plan", "orphan", restorePlan(first));
    assertTrue(plan().expired.isEmpty());
  }

  @Test void pendingOperationKeepsArchiveAndVersionsAtEveryPhase() throws Exception {
    Operation op = new Operation(); op.id = id(70); op.jobId = job.id; op.kind = "DELETE";
    op.oldVersion = old; op.entry = node(old); op.path = old.path(); op.archivePath = old.location();
    for (String phase : List.of("PLANNED", "STAGED", "ARCHIVED")) {
      op.phase = phase; memory.put("operation", op.id, op);
      assertTrue(versions(plan()).isEmpty(), phase);
    }
  }

  @Test void unknownOrOtherJobsVersionsAreNeverCandidates() throws Exception {
    Version orphan = version(77, "unexplained.txt", "unknown", false);
    Version foreign = new Version(id(78), "other", "foreign.txt", "C", "other/versions/foreign", 5,
        Ids.textHash("other"), "2000-01-01T00:00:00Z", "deleted");
    memory.put("version", foreign.id(), foreign);
    Plan p = plan();
    assertFalse(versions(p).stream().anyMatch(i -> i.versionIds().contains(orphan.id()) || i.versionIds().contains(foreign.id())));
    assertEquals(List.of(old.id()), versions(p).stream().flatMap(i -> i.versionIds().stream()).toList());
  }

  @Test void unknownAliasAtSamePhysicalLocationStopsReclamation() throws Exception {
    alias(old);
    assertTrue(versions(plan()).isEmpty());
  }

  @Test void cancellationRequestedOnArchiveDeviceIsStillADeletionBlocker() throws Exception {
    when(memory.store.decrypt(anyString())).thenAnswer(i -> i.getArgument(0));
    DeviceExecution.Command command = new DeviceExecution.Command(); command.id = id(80);
    command.status = "CANCEL_REQUESTED"; command.deadline = 0;
    var source = new DeviceExecution.BoundEndpoint(config.endpoint("C"), "", "");
    command.payload = Json.write(new DeviceExecution.Work(command.id, "HASH", source, old.location(),
        null, "", old.size(), old.hash(), 0));
    memory.put("device-pending", command.id, command);
    assertThrows(Exception.class, this::plan);
    command.status = "CANCELLED"; memory.put("device-pending", command.id, command);
    assertDoesNotThrow(this::plan);
  }

  @Test void fingerprintsAreStableAcrossPreviewsAndIncludeFullSnapshotJson() throws Exception {
    Plan a = plan(), b = plan();
    assertNotEquals(a.id, b.id);
    assertEquals(a.fingerprint, b.fingerprint);
    Snapshot changed = new Snapshot(first.id(), first.jobId(), first.sequence(), "2023-12-01T00:00:00Z",
        first.completedAt(), first.entries(), first.manifestHash(), first.totalBytes());
    memory.put("snapshot", first.id(), changed);
    assertNotEquals(a.fingerprint, plan().fingerprint);
  }

  @Test void fingerprintChangesWhenVersionReferenceGraphChanges() throws Exception {
    String firstHash = plan().fingerprint;
    Version changed = new Version(old.id(), old.jobId(), old.path(), old.endpointId(), old.location(),
        old.size(), old.hash(), "2001-01-01T00:00:00Z", old.reason());
    memory.put("version", old.id(), changed);
    assertNotEquals(firstHash, plan().fingerprint);
    String secondHash = plan().fingerprint;
    addRestore("PAUSED");
    assertNotEquals(secondHash, plan().fingerprint);
  }

  @Test void remainingProtectionIsRecomputedAfterSnapshotRetirement() throws Exception {
    Item item = versions(plan()).get(0);
    assertTrue(planner.protectedLocations(job.id).contains(ArchivePaths.identity(item)));
    memory.objects.get("snapshot").remove(first.id());
    assertFalse(planner.protectedLocations(job.id).contains(ArchivePaths.identity(item)));
    memory.put("current", job.id, new Node[] {node(old)});
    assertTrue(planner.protectedLocations(job.id).contains(ArchivePaths.identity(item)));
  }

  @Test void batchedRegistrationCheckDetectsNewPhysicalAliasesAndMissingVersions() throws Exception {
    List<Item> items = plan().items;
    assertTrue(planner.mismatchedItems(items).isEmpty());
    Item version = versions(plan()).get(0);
    alias(old);
    assertTrue(planner.mismatchedItems(items).contains(version.id()));
    memory.objects.get("version").remove(id(900));
    memory.objects.get("version").remove(old.id());
    assertTrue(planner.mismatchedItems(items).contains(version.id()));
    memory.objects.get("snapshot").remove(first.id());
    Item manifest = items.stream().filter(i -> i.kind().equals("MANIFEST")).findFirst().orElseThrow();
    assertTrue(planner.matchesRegistered(manifest)); // Durable retirement precedes physical deletion.
  }

  @Test void missingOrCorruptRetainedAndExpiredMetadataFailsClosed() throws Exception {
    memory.objects.get("version").remove(latest.id());
    assertThrows(Exception.class, this::plan);
    memory.put("version", latest.id(), latest);
    memory.objects.get("version").remove(old.id());
    assertThrows(Exception.class, this::plan);
    memory.put("version", old.id(), old);
    memory.put("snapshot", first.id(), new Snapshot(first.id(), first.jobId(), first.sequence(),
        first.startedAt(), first.completedAt(), first.entries(), Ids.textHash("wrong"), first.totalBytes()));
    assertThrows(Exception.class, this::plan);
  }

  @Test void wrongVersionOwnershipAndMissingCheckpointFailClosed() throws Exception {
    memory.put("version", old.id(), new Version(old.id(), "other", old.path(), old.endpointId(),
        old.location(), old.size(), old.hash(), old.createdAt(), old.reason()));
    assertThrows(Exception.class, this::plan);
    memory.put("version", old.id(), old);
    memory.put("sync-checkpoint", job.id, new SyncEngine.Checkpoint(newest.completedAt(), id(999), 1));
    assertThrows(Exception.class, this::plan);
  }

  @Test void candidateNamespaceAndVersionSuffixAreStrictlyValidated() throws Exception {
    Item original = versions(plan()).get(0);
    for (String path : List.of("../escape", "job/versions/../escape", "other/versions/" + old.id(),
        ".syncsrv/staging/file", "job/versions/unknown.txt", "job/versions/dir\\file")) {
      Item unsafe = new Item(ArchivePaths.itemId("C", path), "VERSION", "C", path, original.size(),
          original.hash(), original.versionIds(), "");
      assertThrows(Exception.class, () -> ArchivePlanner.validatePath(job.id, unsafe), path);
    }
  }

  @Test void invalidOrFutureMonthsAreRejectedButCurrentMonthIsAllowed() throws Exception {
    for (String month : List.of("2024-3", "2024-00", "2024-13", "0000-01", "bad",
        YearMonth.now(ZoneId.of("Asia/Shanghai")).plusMonths(1).toString()))
      assertThrows(IllegalArgumentException.class, () -> planner.plan(job.id, new Request(month, List.of())));
    assertDoesNotThrow(() -> planner.plan(job.id,
        new Request(YearMonth.now(ZoneId.of("Asia/Shanghai")).toString(), List.of())));
  }

  @Test void checkpointEntryCountMustMatchItsSnapshot() throws Exception {
    memory.put("sync-checkpoint", job.id, new SyncEngine.Checkpoint(newest.completedAt(), newest.id(), 999));
    assertThrows(Exception.class, this::plan);
  }

  @Test void restorePlanCannotSilentlyPinADifferentSnapshotFromItsRun() throws Exception {
    addRestore("PAUSED");
    memory.put("restore-plan", id(88), restorePlan(baseline));
    assertThrows(Exception.class, this::plan);
  }

  @Test void physicalAliasCaseDifferencesRemainProtectedOnAllPlatforms() throws Exception {
    Item candidate = versions(plan()).get(0);
    memory.put("version", id(900), new Version(id(900), "other", old.path(), old.endpointId(),
        old.location().toUpperCase(Locale.ROOT), old.size(), old.hash(), old.createdAt(), old.reason()));
    assertTrue(versions(plan()).isEmpty());
    assertTrue(planner.mismatchedItems(List.of(candidate)).contains(candidate.id()));
  }

  @Test void alternateEndpointRootCannotHideAPhysicalAlias() throws Exception {
    Item candidate = versions(plan()).get(0);
    Endpoint alias = new Endpoint(); alias.id = "alias"; alias.root = root.toString();
    when(config.endpoint(alias.id)).thenReturn(alias);
    memory.put("version", id(900), new Version(id(900), "other", old.path(), alias.id,
        "C/" + old.location(), old.size(), old.hash(), old.createdAt(), old.reason()));
    assertTrue(versions(plan()).isEmpty());
    assertTrue(planner.mismatchedItems(List.of(candidate)).contains(candidate.id()));
  }

  @Test void mappedDeviceAliasesAcrossProtocolsAreProtected() throws Exception {
    Item candidate = versions(plan()).get(0);
    Endpoint archive = config.endpoint("C"); archive.agentId = "nas"; archive.agentRoot = "/volume1/C";
    Endpoint alias = new Endpoint(); alias.id = "alias"; alias.protocol = "SFTP";
    alias.host = "nas.test"; alias.root = "/archive"; alias.agentId = "nas"; alias.agentRoot = "/volume1";
    when(config.endpoint(alias.id)).thenReturn(alias);
    memory.put("version", id(900), new Version(id(900), "other", old.path(), alias.id,
        "C/" + old.location(), old.size(), old.hash(), old.createdAt(), old.reason()));
    assertTrue(versions(plan()).isEmpty());
    assertTrue(planner.mismatchedItems(List.of(candidate)).contains(candidate.id()));
  }

  @Test void manifestWithAnUnexpectedVersionAliasIsRetiredButNotDeleted() throws Exception {
    Plan p = plan();
    Item manifest = p.items.stream().filter(i -> i.kind().equals("MANIFEST")).findFirst().orElseThrow();
    memory.put("version", id(900), new Version(id(900), "other", "manifest.json", "C",
        manifest.location(), manifest.size(), manifest.hash(), old.createdAt(), "current"));
    Plan guarded = plan();
    assertEquals(1, guarded.expired.size());
    assertFalse(guarded.items.stream().anyMatch(i -> i.id().equals(manifest.id())));
    assertTrue(planner.mismatchedItems(List.of(manifest)).contains(manifest.id()));
  }

  @Test void archiveIdentityBindsAccountRealmAndServerKeyButAllowsPasswordRotation() throws Exception {
    Endpoint archive = config.endpoint("C");
    archive.protocol = "SFTP"; archive.host = "nas.test"; archive.root = "/archive";
    archive.username = "backup"; archive.domain = "realm"; archive.hostKey = "key-one";
    String original = planner.endpointFingerprint(job.id);
    archive.username = "other";
    assertNotEquals(original, planner.endpointFingerprint(job.id), "a different login can resolve to another chroot");
    archive.username = "backup"; archive.domain = "other-realm";
    assertNotEquals(original, planner.endpointFingerprint(job.id));
    archive.domain = "realm"; archive.hostKey = "key-two";
    assertNotEquals(original, planner.endpointFingerprint(job.id));
    archive.hostKey = "key-one"; archive.password = "rotated-password";
    assertEquals(original, planner.endpointFingerprint(job.id));
  }

  @Test void webdavSchemeRemainsPartOfNamespaceIdentityWithAnExplicitPort() throws Exception {
    Endpoint archive = config.endpoint("C");
    archive.protocol = "WEBDAV"; archive.host = "http://nas.test:443/dav"; archive.port = 443;
    archive.root = "/archive";
    String plain = planner.endpointFingerprint(job.id);
    archive.host = "https://nas.test:443/dav";
    assertNotEquals(plain, planner.endpointFingerprint(job.id));
  }

  @Test void registeredCandidateMustKeepItsJobPathAndArchiveReason() throws Exception {
    Item candidate = versions(plan()).get(0);
    memory.objects.get("snapshot").remove(first.id());
    for (Version changed : List.of(
        new Version(old.id(), "other", old.path(), "C", old.location(), old.size(), old.hash(), old.createdAt(), old.reason()),
        new Version(old.id(), old.jobId(), "another.txt", "C", old.location(), old.size(), old.hash(), old.createdAt(), old.reason()),
        new Version(old.id(), old.jobId(), old.path(), "C", old.location(), old.size(), old.hash(), old.createdAt(), "deleted"))) {
      memory.put("version", old.id(), changed);
      assertTrue(planner.mismatchedItems(List.of(candidate)).contains(candidate.id()), changed.toString());
    }
  }

  @Test void aRetainedSnapshotMustBeAReconstructableDirectoryTree() throws Exception {
    Version nested = version(100, "document.txt/child.txt", "child", true);
    snapshot(13, newest.completedAt(), latest, nested);
    memory.put("sync-checkpoint", job.id, new SyncEngine.Checkpoint(newest.completedAt(), newest.id(), 2));
    assertThrows(Exception.class, this::plan, "a regular file cannot also be a parent directory");
    snapshot(13, newest.completedAt(), nested);
    memory.put("sync-checkpoint", job.id, new SyncEngine.Checkpoint(newest.completedAt(), newest.id(), 1));
    assertThrows(Exception.class, this::plan, "parent directories cannot be omitted from a complete snapshot");
  }

  @Test void physicalAliasesFollowRegisteredBridgeMappingsWithoutAVersionOnTheBridge() throws Exception {
    Endpoint archive = config.endpoint("C"); archive.agentId = "first"; archive.agentRoot = "/first/C";
    Endpoint bridge = new Endpoint(); bridge.id = "bridge"; bridge.root = archive.root;
    bridge.agentId = "second"; bridge.agentRoot = "/second/C";
    Endpoint alias = new Endpoint(); alias.id = "alias"; alias.protocol = "SFTP";
    alias.host = "second.test"; alias.root = "/remote"; alias.agentId = "second"; alias.agentRoot = "/second";
    when(config.endpoint(bridge.id)).thenReturn(bridge); when(config.endpoint(alias.id)).thenReturn(alias);
    when(config.endpoints()).thenReturn(List.of(archive, bridge, alias));
    Item candidate = versions(plan()).get(0);
    memory.put("version", id(900), new Version(id(900), "other", old.path(), alias.id,
        "C/" + old.location(), old.size(), old.hash(), old.createdAt(), old.reason()));
    assertTrue(versions(plan()).isEmpty());
    assertTrue(planner.mismatchedItems(List.of(candidate)).contains(candidate.id()));
  }

  @Test void unicodeEquivalentPhysicalAliasesAreProtectedOnNormalizingFilesystems() throws Exception {
    Version named = version(1, "café.txt", "old", false);
    snapshot(11, first.completedAt(), named);
    Item candidate = versions(plan()).get(0);
    String decomposed = java.text.Normalizer.normalize(named.location(), java.text.Normalizer.Form.NFD);
    assertNotEquals(named.location(), decomposed);
    assertEquals(ArchivePaths.identity("C", named.location()), ArchivePaths.identity("C", decomposed));
    memory.put("version", id(900), new Version(id(900), "other", "alias.txt", "C", decomposed,
        named.size(), named.hash(), named.createdAt(), "current"));
    assertTrue(versions(plan()).isEmpty());
    assertTrue(planner.mismatchedItems(List.of(candidate)).contains(candidate.id()));
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.MAC)
  void macNormalizationFixtureResolvesToOnePhysicalFile() throws Exception {
    Path composed = root.resolve("café.txt");
    Path decomposed = root.resolve(java.text.Normalizer.normalize("café.txt", java.text.Normalizer.Form.NFD));
    Files.writeString(composed, "normalization fixture");
    assertTrue(Files.isSameFile(composed, decomposed));
  }

  @Test void mostRecentSequenceSurvivesAClockRollbackEvenWithoutACheckpoint() throws Exception {
    memory.objects.get("sync-checkpoint").remove(job.id);
    replaceTime(newest, "2023-01-01T00:00:00Z");
    assertFalse(plan().expired.stream().anyMatch(s -> s.id().equals(newest.id())));
  }

  private Plan plan() throws Exception { return planner.plan(job.id, new Request("2024-03", List.of())); }
  private List<Item> versions(Plan p) { return p.items.stream().filter(i -> i.kind().equals("VERSION")).toList(); }
  private static String id(int n) { return String.format("%032x", n); }
  private Node node(Version v) { return new Node(v.path(), false, v.size(), 0, v.hash(), v.id()); }
  private Version version(int n, String path, String content, boolean current) {
    String location = current ? path : job.id + "/versions/" + path + ".__v" + id(n) + ".__replaced";
    Version v = new Version(id(n), job.id, path, current ? "B" : "C", location,
        content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, Ids.textHash(content),
        "2000-01-01T00:00:00Z", current ? "current" : "replaced");
    memory.put("version", v.id(), v); return v;
  }
  private Snapshot snapshot(int n, String time, Version... versions) {
    List<Node> nodes = Arrays.stream(versions).map(this::node).sorted(Comparator.comparing(Node::path)).toList();
    Snapshot s = new Snapshot(id(n), job.id, n, time, time, nodes, Ids.hash(Json.bytes(nodes)),
        nodes.stream().mapToLong(Node::size).sum());
    memory.put("snapshot", s.id(), s); return s;
  }
  private void replaceTime(Snapshot s, String time) {
    memory.put("snapshot", s.id(), new Snapshot(s.id(), s.jobId(), s.sequence(), time, time,
        s.entries(), s.manifestHash(), s.totalBytes()));
  }
  private RestoreService.Plan restorePlan(Snapshot s) {
    List<RestoreItem> items = s.entries().stream().filter(n -> !n.directory()).map(n -> {
      Version v = memoryValue(n.versionId());
      return new RestoreItem(n.path(), v.id(), v.endpointId(), v.location(), v.hash(), v.size(), "HISTORY_C");
    }).toList();
    return new RestoreService.Plan(s, items, List.of());
  }
  private Version memoryValue(String id) {
    try { return memory.store.get("version", id, Version.class); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }
  private void addRestore(String status) {
    Run r = new Run(); r.id = id(88); r.kind = "RESTORE"; r.jobId = job.id;
    r.snapshotId = first.id(); r.status = status;
    memory.put("run", r.id, r); memory.put("restore-plan", r.id, restorePlan(first));
  }
  private void alias(Version original) {
    memory.put("version", id(900), new Version(id(900), "other", original.path(), original.endpointId(),
        original.location(), original.size(), original.hash(), original.createdAt(), original.reason()));
  }
}
