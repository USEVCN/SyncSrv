package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.archive.*;
import com.oadig.syncsrv.modules.archive.ArchiveModels.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ArchiveDeferredTest {
  @TempDir Path root;
  MemoryStore memory;
  ConfigurationService config;
  ArchivePlanner planner;
  Job job;
  Version old, current;
  Snapshot first;
  Plan completed;

  @BeforeEach void setup() throws Exception {
    memory = new MemoryStore(); config = mock(ConfigurationService.class);
    job = new Job(); job.id = "job"; job.sourceId = "A"; job.targetId = "B"; job.archiveId = "C";
    when(config.job(job.id)).thenReturn(job);
    for (String id : List.of("B", "C")) {
      Endpoint endpoint = new Endpoint(); endpoint.id = id;
      endpoint.root = Files.createDirectory(root.resolve(id)).toString();
      when(config.endpoint(id)).thenReturn(endpoint);
    }
    old = new Version(id(1), job.id, "f.txt", "C", "job/versions/f.txt.__v" + id(1) + ".__replaced",
        3, Ids.textHash("old"), "2024-01-01T00:00:00Z", "replaced");
    current = new Version(id(2), job.id, "f.txt", "B", "f.txt", 3, Ids.textHash("now"),
        "2024-02-20T00:00:00Z", "current");
    memory.put("version", old.id(), old); memory.put("version", current.id(), current);
    first = point(11, "2024-01-01T00:00:00Z", old);
    point(12, "2024-02-20T00:00:00Z", current);
    Snapshot latest = point(13, "2024-03-10T00:00:00Z", current);
    memory.put("sync-checkpoint", job.id, new SyncEngine.Checkpoint(latest.completedAt(), latest.id(), 1));
    memory.put("current", job.id, new Node[] {node(current)});
    planner = new ArchivePlanner(memory.store, config);
    completed = planner.plan(job.id, new Request("2024-03", List.of()));
    completed.state = "COMPLETED"; completed.completedAt = Ids.now();
    memory.put("archive-plan", completed.id, completed);
    memory.put("archive-retirement", completed.id,
        new ArchiveService.Retirement(completed.id, id(90), Ids.now(), definition(completed)));
    for (Item item : completed.items) {
      String key = Ids.textHash(completed.id + ":" + item.id());
      memory.put("archive-item", key, Map.of("planId", completed.id, "itemId", item.id(),
          "state", item.kind().equals("VERSION") ? "PROTECTED" : "REMOVED", "runId", id(90),
          "time", Ids.now(), "deletedBytes", 0, "note", "fixture"));
    }
    memory.objects.get("snapshot").remove(first.id());
  }

  @Test void aPreviouslyProtectedVersionCanBeCollectedAfterItsReferencesDisappear() throws Exception {
    assertTrue(candidates().contains(old.id()));
    assertEquals(0, preview("2024-03").expired.size());
  }

  @Test void stillReferencedDeferredContentIsNotCollected() throws Exception {
    memory.put("current", job.id, new Node[] {node(old)});
    assertFalse(candidates().contains(old.id()));
  }

  @Test void changedVersionContentLocationOrOwnershipIsNotTrustedAsOldProvenance() throws Exception {
    for (Version changed : List.of(
        new Version(old.id(), job.id, old.path(), "C", old.location(), 3, Ids.textHash("bad"), old.createdAt(), old.reason()),
        new Version(old.id(), job.id, old.path(), "C", "elsewhere", 3, old.hash(), old.createdAt(), old.reason()),
        new Version(old.id(), "other", old.path(), "C", old.location(), 3, old.hash(), old.createdAt(), old.reason()))) {
      memory.put("version", old.id(), changed);
      assertFalse(candidates().contains(old.id()));
    }
  }

  @Test void bareOrTamperedPlanAndMismatchedOutcomeDoNotAuthorizeCleanup() throws Exception {
    memory.objects.get("archive-retirement").remove(completed.id);
    assertFalse(candidates().contains(old.id()));
    memory.put("archive-retirement", completed.id,
        new ArchiveService.Retirement(completed.id, id(90), Ids.now(), "wrong"));
    assertFalse(candidates().contains(old.id()));
    memory.put("archive-retirement", completed.id,
        new ArchiveService.Retirement(completed.id, id(90), Ids.now(), definition(completed)));
    Item item = completed.items.stream().filter(i -> i.kind().equals("VERSION")).findFirst().orElseThrow();
    memory.put("archive-item", Ids.textHash(completed.id + ":" + item.id()),
        Map.of("planId", "other", "itemId", item.id(), "state", "PROTECTED", "runId", id(90),
            "time", Ids.now(), "deletedBytes", 0, "note", "fixture"));
    assertFalse(candidates().contains(old.id()));
  }

  @Test void aNarrowerCutoffDoesNotReuseBroaderPastPermission() throws Exception {
    assertFalse(preview("2024-02").items.stream().anyMatch(i -> i.versionIds().contains(old.id())));
  }

  @Test void unknownOrphansRemainOutsideCleanupEvenWhenDeferredEvidenceExists() throws Exception {
    Version orphan = new Version(id(3), job.id, "unknown", "C", "job/versions/unknown.__v" + id(3) + ".__deleted",
        1, Ids.textHash("x"), old.createdAt(), "deleted");
    memory.put("version", orphan.id(), orphan);
    assertTrue(candidates().contains(old.id())); assertFalse(candidates().contains(orphan.id()));
  }

  private Set<String> candidates() throws Exception {
    Set<String> ids = new HashSet<>();
    preview("2024-03").items.forEach(item -> ids.addAll(item.versionIds()));
    return ids;
  }
  private Plan preview(String month) throws Exception { return planner.plan(job.id, new Request(month, List.of())); }
  private static String id(int n) { return String.format("%032x", n); }
  private Node node(Version v) { return new Node(v.path(), false, v.size(), 0, v.hash(), v.id()); }
  private Snapshot point(int id, String time, Version v) {
    List<Node> entries = List.of(node(v));
    Snapshot point = new Snapshot(id(id), job.id, id, time, time, entries, Ids.hash(Json.bytes(entries)), v.size());
    memory.put("snapshot", point.id(), point); return point;
  }
  private String definition(Plan p) {
    return Ids.hash(Json.bytes(List.of(p.jobId, p.beforeMonth, p.cutoff, p.endpointFingerprint,
        p.fingerprint, p.expired, p.items)));
  }
}
