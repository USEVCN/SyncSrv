package com.oadig.syncsrv.modules.archive;

import com.oadig.syncsrv.modules.archive.ArchiveModels.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Models.*;
import java.time.Instant;
import java.util.*;

/** A skipped file remains eligible only through its sealed, retired cleanup plan and receipt. */
final class ArchiveDeferred {
  private ArchiveDeferred() {}

  static Set<String> versions(Store store, Job job, Instant cutoff, Map<String, Version> versions,
      String endpointFingerprint) throws Exception {
    Set<String> result = new TreeSet<>();
    for (Plan plan : store.list("archive-plan", Plan.class)) {
      if (!job.id.equals(plan.jobId) || !"COMPLETED".equals(plan.state) || plan.items == null
          || !endpointFingerprint.equals(plan.endpointFingerprint)) continue;
      Instant previousCutoff;
      try { previousCutoff = Instant.parse(plan.cutoff); } catch (RuntimeException malformed) { continue; }
      if (cutoff.isBefore(previousCutoff)) continue;
      var retirement = store.get("archive-retirement", plan.id, ArchiveService.Retirement.class);
      if (retirement == null || !plan.id.equals(retirement.planId())
          || !retirement.definitionHash().equals(ArchiveService.definition(plan))) continue;
      List<Item> candidates = plan.items.stream().filter(i -> "VERSION".equals(i.kind())).toList();
      var outcomes = store.getMany("archive-item", candidates.stream()
          .map(i -> ArchiveFiles.key(plan.id, i.id())).toList(), ArchiveFiles.Outcome.class);
      for (Item item : candidates) {
        var outcome = outcomes.get(ArchiveFiles.key(plan.id, item.id()));
        if (outcome == null || !"PROTECTED".equals(outcome.state()) || !plan.id.equals(outcome.planId())
            || !item.id().equals(outcome.itemId()) || !job.archiveId.equals(item.endpointId())) continue;
        boolean matching = true;
        for (String id : item.versionIds()) {
          Version version = versions.get(id);
          if (version == null || !ArchivePaths.registered(item, version)) { matching = false; break; }
        }
        if (matching) result.addAll(item.versionIds());
      }
    }
    return Set.copyOf(result);
  }
}
