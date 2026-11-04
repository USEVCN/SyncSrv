package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.oadig.syncsrv.modules.auth.*;
import com.oadig.syncsrv.modules.jobs.*;
import com.oadig.syncsrv.shared.Models.Run;
import com.oadig.syncsrv.shared.web.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RunDispositionApiTest {
  private MockMvc api;
  private Cookie session;
  private MemoryStore memory;
  private static final String PATH = "/api/runs/failed/disposition";
  private static final String BODY =
      """
      {"revision":0,"reason":"网络中断","plan":"恢复连接","method":"检查交换机","actor":"spoofed"}
      """;

  @BeforeEach
  void setup() throws Exception {
    memory = new MemoryStore();
    Run run = new Run();
    run.id = "failed";
    run.status = "FAILED";
    run.error = "Original error";
    memory.put("run", run.id, run);
    var auth = new AuthService(memory.store, "FixturePassword2026");
    session = new Cookie("SYNC_SESSION", auth.login("admin", "FixturePassword2026", "fixture"));
    api =
        MockMvcBuilders.standaloneSetup(
                new RunDispositionController(new RunDispositions(memory.store)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new AuthFilter(auth))
            .build();
  }

  @Test
  void authenticatedSaveUsesServerActorAndReadsBackVersionedData() throws Exception {
    api.perform(
            put(PATH)
                .cookie(session)
                .header("X-SyncSrv-Request", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revision").value(1))
        .andExpect(jsonPath("$.data.actor").value("admin"));
    api.perform(get(PATH).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.originalError").value("Original error"))
        .andExpect(jsonPath("$.data.latest.reason").value("网络中断"))
        .andExpect(jsonPath("$.data.history[0].method").value("检查交换机"));
    api.perform(
            put(PATH)
                .cookie(session)
                .header("X-SyncSrv-Request", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isConflict());
  }

  @Test
  void unauthenticatedAndCrossOriginNotesCannotBeWritten() throws Exception {
    int before = memory.actions.size();
    api.perform(get(PATH)).andExpect(status().isUnauthorized());
    api.perform(
            put(PATH)
                .header("X-SyncSrv-Request", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isUnauthorized());
    api.perform(
            put(PATH)
                .cookie(session)
                .header("X-SyncSrv-Request", "1")
                .header("Origin", "https://untrusted.example")
                .header("Host", "localhost")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isForbidden());
    assertEquals(before, memory.actions.size());
  }
}
