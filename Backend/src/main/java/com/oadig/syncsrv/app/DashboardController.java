package com.oadig.syncsrv.app;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Models.*;
import com.oadig.syncsrv.shared.web.ApiResponse;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DashboardController {
  private final Store store;
  private final ConfigurationService config;
  private final TaskManager tasks;
  private final SyncEngine sync;

  @Value("${server.port}")
  private int port;

  @Value("${server.address:127.0.0.1}")
  private String bindAddress;

  public DashboardController(
      Store store, ConfigurationService config, TaskManager tasks, SyncEngine sync) {
    this.store = store;
    this.config = config;
    this.tasks = tasks;
    this.sync = sync;
  }

  @GetMapping("/health")
  public ApiResponse<?> health() throws Exception {
    return ApiResponse.ok(
        Map.of(
            "service", "SyncSrv", "version", com.oadig.syncsrv.shared.BuildIdentity.version(), "database", store.healthy() ? "UP" : "DOWN"));
  }

  @GetMapping("/state")
  public ApiResponse<?> state(jakarta.servlet.http.HttpServletRequest request) throws Exception {
    return ApiResponse.ok(
        Map.of(
            "totals",store.historyCounts(),
            "currentUser", Map.of("username",Objects.toString(request.getAttribute("actor"),""),"role",Objects.toString(request.getAttribute("role"),"")),
            "endpoints",
            config.endpoints(),
            "jobs",
            config.jobs(),
            "runs",
            tasks.runs(),
            "snapshots",
            store.recent("snapshot-summary", 200, SnapshotSummary.class),
            "settings",
            tasks.settings(),
            "uiSettings", new com.oadig.syncsrv.modules.settings.UiSettings(store).get(),
            "audit",
            store.head(),
            "system",
            Map.of(
                "version",
                com.oadig.syncsrv.shared.BuildIdentity.version(),
                "java",
                System.getProperty("java.version"),
                "database",
                store.databaseEngine(),
                "port",
                port,
                "bindAddress", bindAddress,
                "maxConcurrent",
                1)));
  }
}
