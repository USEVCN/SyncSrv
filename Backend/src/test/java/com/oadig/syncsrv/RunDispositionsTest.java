package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.jobs.RunDispositions;
import com.oadig.syncsrv.shared.Models.Run;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RunDispositionsTest {
  private MemoryStore memory() throws Exception {
    MemoryStore memory = new MemoryStore();
    Run run = new Run();
    run.id = "failed";
    run.status = "FAILED";
    run.error = "SMB connection reset";
    memory.put("run", run.id, run);
    return memory;
  }

  @Test
  void revisionsPersistWithAuditWithoutRewritingOriginalRun() throws Exception {
    MemoryStore memory = memory();
    var service = new RunDispositions(memory.store);
    assertNull(service.read("failed", 0).latest());
    var first = service.save("failed", new RunDispositions.Input(0, " NAS 断线 ", "", ""), "alice");
    assertEquals("NAS 断线", first.reason());
    assertEquals(1, first.revision());
    var second =
        service.save(
            "failed", new RunDispositions.Input(1, "NAS 断线", "修复连接后续传", "已修复交换机端口"), "bob");
    var view = new RunDispositions(memory.store).read("failed", 0);
    assertEquals(second, view.latest());
    assertEquals(first, view.history().get(1));
    assertEquals("bob", view.latest().actor());
    assertFalse(view.latest().savedAt().isBlank());
    assertEquals(2, memory.actions.stream().filter("run.disposition_saved"::equals).count());
    Run original = memory.store.get("run", "failed", Run.class);
    assertEquals("FAILED", original.status);
    assertEquals("SMB connection reset", original.error);
    assertEquals(
        second,
        service.save(
            "failed",
            new RunDispositions.Input(2, second.reason(), second.plan(), second.method()),
            "bob"));
    assertEquals(2, memory.actions.size());
  }

  @Test
  void staleEditorCannotOverwriteNewerInvestigation() throws Exception {
    var memory = memory();
    var service = new RunDispositions(memory.store);
    service.save("failed", new RunDispositions.Input(0, "首次排查", "", ""), "alice");
    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.save("failed", new RunDispositions.Input(0, "过期修改", "", ""), "bob"));
    assertEquals(409, error.getStatusCode().value());
    assertEquals("首次排查", service.read("failed", 0).latest().reason());
    assertEquals(1, memory.actions.size());
  }

  @Test
  void validatesFailedExecutionAndTextBeforeAnyAuditWrite() throws Exception {
    var memory = memory();
    var service = new RunDispositions(memory.store);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.save("failed", new RunDispositions.Input(0, " ", null, ""), "alice"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.save(
                "failed", new RunDispositions.Input(0, "x".repeat(4001), "", ""), "alice"));
    assertThrows(ResponseStatusException.class, () -> service.read("missing", 0));
    Run complete = new Run();
    complete.id = "completed";
    complete.status = "COMPLETED";
    memory.put("run", complete.id, complete);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.save("completed", new RunDispositions.Input(0, "x", "", ""), "alice"));
    assertTrue(memory.actions.isEmpty());
  }

  @Test
  void historyIsPagedNewestFirstWithoutDroppingOlderVersions() throws Exception {
    var memory = memory();
    var service = new RunDispositions(memory.store);
    for (int i = 0; i < 23; i++)
      service.save("failed", new RunDispositions.Input(i, "排查 " + i, "", ""), "alice");
    var page = service.read("failed", 0);
    assertEquals(20, page.history().size());
    assertEquals(23, page.history().get(0).revision());
    assertEquals(4, page.nextBefore());
    var older = service.read("failed", page.nextBefore());
    assertEquals(3, older.history().size());
    assertEquals(1, older.history().get(2).revision());
    assertEquals(0, older.nextBefore());
  }
}
