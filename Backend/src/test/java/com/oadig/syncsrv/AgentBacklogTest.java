package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.*;
import com.oadig.syncsrv.shared.Models.Job;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentBacklogTest {
  final MemoryStore memory = new MemoryStore();
  final ConfigurationService config = mock(ConfigurationService.class);
  final Instant start = Instant.parse("2026-09-19T00:00:00Z");
  final Job job = new Job();
  AgentBacklogTest() throws Exception {
    job.id = "job"; job.name = "备份测试"; job.agentId = "agent"; job.enabled = true; job.intervalSeconds = 30;
    when(config.jobs()).thenReturn(List.of(job));
  }
  void row(String id, String status, String phase, int received) {
    var entry = new SourceChanges.Entry();
    entry.id = id; entry.jobId = "job"; entry.agentId = "agent"; entry.path = id;
    entry.status = status; entry.phase = phase; entry.receivedAt = start.plusSeconds(received).toString();
    entry.updatedAt = entry.receivedAt;
    memory.put("source-change", id, entry);
  }
  AgentBacklog at(int seconds) { return new AgentBacklog(memory.store, config, Clock.fixed(start.plusSeconds(seconds), ZoneOffset.UTC)); }
  @Test void yellowBeginsFiveMinutesAfterQuietTimeAndDoesNotIncludeActiveOrCenterWork() throws Exception {
    row("waiting", "WAITING", "DEBOUNCING", 0);
    row("active", "RUNNING", "UPLOADING_B", 0);
    row("center", "RUNNING", "CENTER_BACKUP", 0);
    row("ready", "READY", "WAITING_COMMIT", 0);
    row("done", "COMPLETED", "COMPLETE", 0);
    assertEquals(0, at(329).view("agent", 1).summary().overdue());
    var result = at(330).view("agent", 1);
    assertEquals(2, result.total()); assertEquals(1, result.summary().overdue());
    assertEquals(1, result.summary().running()); assertEquals(1, result.summary().waiting());
    job.enabled = false;
    assertEquals(0, at(1000).view("agent", 1).summary().overdue());
  }
  @Test void newerEditsUseTheSameJobQuietPeriodAsExecution() throws Exception {
    row("old", "WAITING", "DEBOUNCING", 0);
    row("new", "WAITING", "DEBOUNCING", 320);
    assertEquals(0, at(330).view("agent", 1).summary().overdue());
    assertEquals(2, at(650).view("agent", 1).summary().overdue());
  }
  @Test void paginationKeepsOldestWaitingPathsAndCountsBeyondFiveHundred() throws Exception {
    for (int i = 0; i < 551; i++) row("file-" + i, "WAITING", "DEBOUNCING", i);
    var result = at(1000).view("agent", 1);
    assertEquals(551, result.total()); assertEquals(551, result.summary().overdue());
    assertEquals("file-0", result.entries().get(0).change().path);
    assertEquals(1, at(1000).view("agent", 12).entries().size());
    assertEquals(0, at(1000).view("another-agent", 1).total());
    job.agentId = "new-owner";
    assertEquals(0, at(1000).view("agent", 1).total());
  }
}
