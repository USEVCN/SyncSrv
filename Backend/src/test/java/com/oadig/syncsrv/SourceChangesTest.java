package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.*;
import com.oadig.syncsrv.shared.Models.Job;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceChangesTest {
  final Instant now = Instant.parse("2026-09-18T00:00:00Z");
  final MemoryStore memory = new MemoryStore();
  final ConfigurationService config = mock(ConfigurationService.class);
  final Job job = new Job();
  SourceChangesTest() throws Exception {
    job.id = "job"; job.agentId = "agent"; job.sourceMode = "AGENT";
    job.discoveryMode = "EVENTS"; job.enabled = true; job.intervalSeconds = 10;
    when(config.job("job")).thenReturn(job);
    var device = new AgentService.Device(); device.id = "agent"; device.status = "APPROVED";
    memory.put("agent", "agent", device);
  }
  SourceChanges at(int seconds) { return new SourceChanges(memory.store, config, Clock.fixed(now.plusSeconds(seconds), ZoneOffset.UTC)); }
  ChangeInbox.Batch batch(String id) { return new ChangeInbox.Batch("epoch", List.of(new ChangeInbox.Event(id, "job", "file", "CHANGE", now.toString()))); }
  SourceChanges.Entry row() throws Exception { return at(0).view().entries().get(0); }

  @Test void noticeIsDurableAndVisibleBeforeAnyManifestOrExecution() throws Exception {
    assertEquals(List.of("1"), at(0).receive("agent", batch("1")));
    assertEquals("WAITING", row().status);
    assertTrue(new ChangeInbox(memory.store).pending(job.id).isEmpty());
    assertNull(AgentSource.state(memory.store, job.id));
    assertTrue(memory.actions.contains("source.changes_notified"));
    assertFalse(at(9).start("agent", batch("1")).allowed());
    assertTrue(at(10).start("agent", batch("1")).allowed());
    assertEquals("RUNNING", row().status);
  }
  @Test void frequentSavesResetServerQuietTimeButDuplicateOrLateDeliveryDoesNot() throws Exception {
    at(0).receive("agent", batch("1"));
    at(8).receive("agent", batch("2"));
    at(9).receive("agent", batch("1"));
    at(12).receive("agent", batch("2"));
    assertEquals("2", row().eventId);
    assertEquals(5000, at(13).start("agent", batch("2")).waitMillis());
    assertFalse(at(20).start("agent", batch("1")).allowed());
    assertTrue(at(18).start("agent", batch("2")).allowed());
  }
  @Test void editingIntervalAtCenterTakesEffectWithoutWaitingForAgentConfigPoll() throws Exception {
    at(0).receive("agent", batch("1"));
    job.intervalSeconds = 60;
    assertEquals(50000, at(10).start("agent", batch("1")).waitMillis());
    job.intervalSeconds = 1;
    assertTrue(at(10).start("agent", batch("1")).allowed());
  }
  @Test void uploadAckIsNotBackupCompletionAndNewGenerationSurvivesOldCompletion() throws Exception {
    at(0).receive("agent", batch("1"));
    at(10).start("agent", batch("1"));
    at(11).ready("agent", batch("1"));
    assertEquals("READY", row().status);
    var inbox = new ChangeInbox(memory.store);
    inbox.receive("agent", batch("1"));
    var claim = at(12).claim(inbox.claim("job"), "run1");
    assertEquals("CENTER_BACKUP", row().phase);
    at(13).receive("agent", batch("2"));
    at(14).finish(claim, "");
    at(14).ready("agent", batch("1"));
    assertEquals("WAITING", row().status);
    assertEquals("2", row().eventId);
    assertEquals("", row().completedAt);
  }
  @Test void failureRetriesAndOnlySuccessfulCenterCommitCompletes() throws Exception {
    at(0).receive("agent", batch("1"));
    at(10).start("agent", batch("1"));
    at(11).failed("agent", batch("1"));
    assertEquals("FAILED", row().status);
    at(12).start("agent", batch("1"));
    at(13).ready("agent", batch("1"));
    var inbox = new ChangeInbox(memory.store); inbox.receive("agent", batch("1"));
    var first = at(14).claim(inbox.claim("job"), "run1");
    at(15).finish(first, "fixture failure");
    assertEquals("FAILED", row().status);
    var retry = at(16).claim(inbox.claim("job"), "run2");
    at(17).finish(retry, "");
    assertEquals("COMPLETED", row().status);
    assertEquals("run2", row().runId);
    at(18).receive("agent", batch("1"));
    assertEquals("COMPLETED", row().status);
    assertTrue(memory.actions.contains("source.changes_commit_failed"));
    assertTrue(memory.actions.contains("source.changes_completed"));
  }
  @Test void pausedOrUnauthorizedSourceCannotStartButPausedChangesAreVisible() throws Exception {
    job.enabled = false;
    at(0).receive("agent", batch("1"));
    assertFalse(at(20).start("agent", batch("1")).allowed());
    job.agentId = "other";
    assertThrows(SecurityException.class, () -> at(21).receive("agent", batch("2")));
  }
  @Test void restartCanReclaimAnInterruptedCenterStage() throws Exception {
    at(0).receive("agent", batch("1")); at(10).start("agent", batch("1")); at(11).ready("agent", batch("1"));
    var inbox = new ChangeInbox(memory.store); inbox.receive("agent", batch("1"));
    at(12).claim(inbox.claim("job"), "interrupted-run");
    var restarted = at(13).claim(inbox.claim("job"), "recovery-run");
    assertEquals(1, restarted.size());
    at(14).finish(restarted, "");
    assertEquals("COMPLETED", row().status);
    assertEquals("recovery-run", row().runId);
  }
  @Test void publicTransportRejectsLegacyFileRelay() throws Exception {
    var agents = mock(AgentService.class);
    var device = new AgentService.Device(); device.id = "agent";
    when(agents.authenticate(any(), any(), any(), any(), any(), any(), any())).thenReturn(device);
    var controller = new AgentPushController(agents, memory.store, config);
    var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/agent/v1/push/begin");
    request.setContent(com.oadig.syncsrv.shared.Json.bytes(new AgentPushService.Begin("job", "a".repeat(32), List.of(""), 0)));
    assertThrows(IllegalArgumentException.class, () -> controller.request("begin", request));
    var chunk = new org.springframework.mock.web.MockHttpServletRequest("POST", "/agent/v1/push/chunk/job/hash/0");
    chunk.setContent(new byte[]{1,2,3});
    assertThrows(IllegalArgumentException.class, () -> controller.chunk("job", "hash", 0, chunk));
    assertFalse(memory.actions.contains("source.upload_started"));
  }

  @Test void jobFilterRunsBeforeLimitAndCountsOnlyThisJobsPaths() throws Exception {
    at(0).receive("agent", batch("1"));
    for (int i = 0; i < 501; i++) {
      var other = new SourceChanges.Entry();
      other.id = "other-" + i; other.jobId = "other"; other.path = "file-" + i;
      other.updatedAt = now.plusSeconds(1).toString(); other.status = "RUNNING";
      memory.put("source-change", other.id, other);
    }
    assertEquals(500, at(0).view().entries().size());
    var filtered = at(0).view("job");
    assertEquals(1, filtered.total());
    assertEquals("job", filtered.entries().get(0).jobId);
    assertEquals(Map.of("WAITING", 1L), filtered.counts());
    assertEquals(0, at(0).view("unknown").total());
  }


  @Test void occupiedFileWaitsWithoutPublishingDeletionOrLosingNewerEvent() throws Exception {
    at(0).receive("agent", batch("1")); at(10).start("agent", batch("1"));
    at(11).progress("agent", batch("1"), "SOURCE_BUSY", "file");
    assertEquals("WAITING", row().status); assertEquals("SOURCE_BUSY", row().phase);
    assertTrue(row().error.contains("占用")); assertEquals("", row().completedAt);
    assertNull(AgentSource.state(memory.store, job.id));
    assertTrue(new ChangeInbox(memory.store).pending(job.id).isEmpty());
    assertTrue(memory.actions.contains("source.changes_waiting"));
    assertThrows(IllegalArgumentException.class, () -> at(12).progress("agent", batch("1"), "SOURCE_BUSY", "../outside"));
    at(12).start("agent", batch("1")); assertEquals("", row().error);
    at(13).receive("agent", batch("2"));
    at(14).progress("agent", batch("1"), "SOURCE_BUSY", "file");
    assertEquals("2", row().eventId); assertEquals("DEBOUNCING", row().phase);
  }

  @Test void largeBatchUsesBulkReadsAndFileProgressDoesNotRewriteUnrelatedEvents() throws Exception {
    var events = new ArrayList<ChangeInbox.Event>();
    for (int i = 0; i < 256; i++) events.add(new ChangeInbox.Event("" + (i + 1), "job", "file-" + i, "CHANGE", now.toString()));
    var batch = new ChangeInbox.Batch("epoch", events);
    at(0).receive("agent", batch);
    clearInvocations(config, memory.store);
    assertTrue(at(10).start("agent", batch).allowed());
    verify(config, atMost(2)).job("job");
    verify(memory.store, never()).get(eq("source-change"), anyString(), any());
    clearInvocations(config, memory.store);
    at(11).progress("agent", batch, "UPLOADING_B", "file-42");
    verify(config, times(1)).job("job");
    verify(memory.store, never()).get(eq("source-change"), anyString(), any());
    var rows = at(11).view().entries();
    assertEquals(1, rows.stream().filter(e -> e.phase.equals("UPLOADING_B")).count());
    assertEquals(255, rows.stream().filter(e -> e.phase.equals("PREPARING")).count());
    int commits = memory.actions.size();
    at(12).progress("agent", batch, "UPLOADING_B", "file-42");
    assertEquals(commits, memory.actions.size(), "duplicate progress must not append another audit transaction");
    at(13).failed("agent", batch);
    assertEquals(256, at(13).view().counts().get("FAILED"));
    at(14).ready("agent", batch);
    assertEquals(256, at(14).view().counts().get("READY"));
  }

  @Test void directoryProgressKeepsNewerGenerationAndUnrelatedScopesUntouched() throws Exception {
    var batch = new ChangeInbox.Batch("epoch", List.of(
        new ChangeInbox.Event("1", "job", "folder", "CHANGE", now.toString()),
        new ChangeInbox.Event("2", "job", "outside", "CHANGE", now.toString())));
    at(0).receive("agent", batch);
    at(10).start("agent", batch);
    at(11).progress("agent", batch, "VERIFYING_B", "folder/child.txt");
    assertEquals("VERIFYING_B", at(11).view().entries().stream().filter(e -> e.path.equals("folder")).findFirst().orElseThrow().phase);
    assertEquals("PREPARING", at(11).view().entries().stream().filter(e -> e.path.equals("outside")).findFirst().orElseThrow().phase);
    at(12).receive("agent", new ChangeInbox.Batch("epoch", List.of(
        new ChangeInbox.Event("3", "job", "folder", "CHANGE", now.toString()))));
    at(13).progress("agent", batch, "UPLOADING_B", "folder/child.txt");
    assertEquals("DEBOUNCING", at(13).view().entries().stream().filter(e -> e.path.equals("folder")).findFirst().orElseThrow().phase);
  }

  @Test void occupiedFileDoesNotMakeUnrelatedParallelFilesWait() throws Exception {
    var batch=new ChangeInbox.Batch("epoch",List.of(
        new ChangeInbox.Event("1","job","busy","CHANGE",now.toString()),
        new ChangeInbox.Event("2","job","other","CHANGE",now.toString())));
    at(0).receive("agent",batch);at(10).start("agent",batch);
    at(11).progress("agent",batch,"SOURCE_BUSY","busy");
    var rows=at(11).view().entries();
    assertEquals("WAITING",rows.stream().filter(r->r.path.equals("busy")).findFirst().orElseThrow().status);
    assertEquals("RUNNING",rows.stream().filter(r->r.path.equals("other")).findFirst().orElseThrow().status);
  }

  @Test void stoppingJobKeepsFailedDeliveryWaitingInsteadOfReportingNetworkFailure() throws Exception {
    at(0).receive("agent",batch("1"));at(10).start("agent",batch("1"));job.enabled=false;
    at(11).failed("agent",batch("1"));
    assertEquals("WAITING",row().status);assertEquals("DEBOUNCING",row().phase);assertEquals("",row().error);
    assertEquals("1",row().eventId);assertNull(AgentSource.state(memory.store,job.id));
  }


  @Test void abortedPausedUploadKeepsEventsAndStopsMisleadingRunningStatus() throws Exception {
    at(0).receive("agent",batch("1"));at(10).start("agent",batch("1"));job.enabled=false;
    at(11).paused("agent",job.id,false);assertEquals("WAITING",row().status);assertEquals("DEBOUNCING",row().phase);
    assertEquals("1",row().eventId);job.enabled=true;
    var device=memory.store.get("agent","agent",AgentService.Device.class);device.status="REVOKED";memory.put("agent","agent",device);
    at(12).paused("agent",job.id,true);assertEquals("AUTHORIZATION_REQUIRED",row().phase);
    device.status="APPROVED";memory.put("agent","agent",device);at(13).start("agent",batch("1"));
    assertEquals("RUNNING",row().status);assertEquals("PREPARING",row().phase);
    at(14).paused("agent",job.id,false);assertEquals("RUNNING",row().status);
  }

}
