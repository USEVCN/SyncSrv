package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.oadig.syncsrv.modules.archive.*;
import com.oadig.syncsrv.modules.auth.*;
import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.shared.Json;
import com.oadig.syncsrv.shared.web.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ArchiveApiTest {
  @TempDir Path root;
  ArchiveCleanupTest.Fixture f;
  TaskManager tasks;
  MockMvc api;
  Cookie login;
  String base="/api/jobs/job/archive";
  @BeforeEach void setup() throws Exception {
    var test=new ArchiveCleanupTest();test.root=root;f=test.new Fixture();
    var auth=new AuthService(f.memory.store,"FixturePassword@2026");
    login=new Cookie("SYNC_SESSION",auth.login("admin","FixturePassword@2026","fixture"));
    tasks=new TaskManager(f.memory.store,f.config,f.sync,new RestoreService(f.memory.store,f.config,f.sync,f.transfer));
    api=MockMvcBuilders.standaloneSetup(new ArchiveController(f.memory.store,f.config,tasks))
        .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new AuthFilter(auth)).build();
  }
  @AfterEach void close() throws Exception {if(tasks!=null)tasks.close();}
  @Test void anonymousAndCrossOriginRequestsCannotPreviewOrExecute() throws Exception {
    for(String path:new String[]{"/plans","/plans/unknown"})api.perform(get(base+path)).andExpect(status().isUnauthorized());
    for(String path:new String[]{"/preview","/execute"}){
      api.perform(post(base+path).header("X-SyncSrv-Request","1").contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isUnauthorized());
      api.perform(post(base+path).cookie(login).header("X-SyncSrv-Request","1").header("Host","localhost")
          .header("Origin","https://untrusted.example").contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isForbidden());
      api.perform(post(base+path).cookie(login).contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isForbidden());
    }
    assertTrue(f.memory.store.list("archive-plan",ArchiveModels.Plan.class).isEmpty());assertFalse(tasks.busy());
  }
  @Test void confirmationCannotBeOmittedOrReusedForAnotherJob() throws Exception {
    var plan=f.preview();var before=ArchiveCleanupTest.contents(f.c);
    for(String content:new String[]{Json.write(Map.of("planId",plan.id)),Json.write(Map.of("planId",plan.id,"confirmed",false)),
        Json.write(Map.of("planId","unknown","confirmed",true))})
      api.perform(post(base+"/execute").cookie(login).header("X-SyncSrv-Request","1")
          .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isBadRequest());
    api.perform(post("/api/jobs/other/archive/execute").cookie(login).header("X-SyncSrv-Request","1")
        .contentType(MediaType.APPLICATION_JSON).content(Json.write(Map.of("planId",plan.id,"confirmed",true))))
        .andExpect(status().isBadRequest());
    assertFalse(tasks.busy());assertEquals(before,ArchiveCleanupTest.contents(f.c));
    assertNotNull(f.sync.snapshot(f.oldest.id()));
  }
  @Test void authenticatedPreviewListsScopeWithoutDeletingAnything() throws Exception {
    var before=ArchiveCleanupTest.contents(f.c);
    api.perform(post(base+"/preview").cookie(login).header("X-SyncSrv-Request","1")
        .contentType(MediaType.APPLICATION_JSON).content(Json.write(Map.of("beforeMonth",f.month))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2))
        .andExpect(jsonPath("$.data.expired.length()").value(1));
    api.perform(get(base+"/plans").cookie(login)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].detailsOmitted").value(true))
        .andExpect(jsonPath("$.data[0].items.length()").value(0));
    var persisted=f.memory.store.list("archive-plan",ArchiveModels.Plan.class).get(0);
    assertFalse(persisted.detailsOmitted);assertEquals(2,persisted.items.size());
    api.perform(get(base+"/plans/"+persisted.id).cookie(login)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(2));
    assertEquals(before,ArchiveCleanupTest.contents(f.c));assertFalse(tasks.busy());
  }
}
