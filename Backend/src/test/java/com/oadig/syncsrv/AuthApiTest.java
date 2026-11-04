package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.oadig.syncsrv.modules.auth.*;
import com.oadig.syncsrv.shared.web.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthApiTest {
  private MockMvc api;
  private AuthService auth;

  @BeforeEach
  void setup() throws Exception {
    MemoryStore memory = new MemoryStore();
    auth = new AuthService(memory.store, "FixturePassword\\@2026");
    api =
        MockMvcBuilders.standaloneSetup(new AuthController(auth))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new AuthFilter(auth))
            .build();
  }

  @Test
  void unauthenticatedAndCrossOriginWritesAreRejected() throws Exception {
    api.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    api.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"bad\"}"))
        .andExpect(status().isForbidden());
    api.perform(
            post("/api/auth/login")
                .header("X-SyncSrv-Request", "1")
                .header("Origin", "https://unexpected.example")
                .header("Host", "localhost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"bad\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void lanOriginLoginWorksWhenProxyPreservesHost() throws Exception {
    var login =
        api.perform(
                post("/api/auth/login")
                    .header("X-SyncSrv-Request", "1")
                    .header("Origin", "http://192.168.50.42:18011")
                    .header("Host", "192.168.50.42:18011")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"admin\",\"password\":\"FixturePassword\\\\@2026\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn();
    String header = login.getResponse().getHeader("Set-Cookie");
    assertNotNull(header);
    assertFalse(header.contains("Secure"));
    String token = header.substring("SYNC_SESSION=".length(), header.indexOf(';'));
    api.perform(
            get("/api/auth/me")
                .header("Host", "192.168.50.42:18011")
                .cookie(new Cookie("SYNC_SESSION", token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("admin"));
  }

  @Test
  void cookieSessionLoginLogoutAndPasswordSemantics() throws Exception {
    var login =
        api.perform(
                post("/api/auth/login")
                    .header("X-SyncSrv-Request", "1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"admin\",\"password\":\"FixturePassword\\\\@2026\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn();
    String header = login.getResponse().getHeader("Set-Cookie");
    assertNotNull(header);
    assertTrue(header.contains("HttpOnly"));
    assertTrue(header.contains("SameSite=Strict"));
    String token = header.substring("SYNC_SESSION=".length(), header.indexOf(';'));
    Cookie cookie = new Cookie("SYNC_SESSION", token);
    api.perform(get("/api/auth/me").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("admin"));
    api.perform(post("/api/auth/logout").header("X-SyncSrv-Request", "1").cookie(cookie))
        .andExpect(status().isOk());
    api.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
  }
}
