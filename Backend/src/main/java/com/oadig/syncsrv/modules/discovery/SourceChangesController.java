package com.oadig.syncsrv.modules.discovery;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Json;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public final class SourceChangesController {
  private final AgentService agents;
  private final SourceChanges changes;
  private final AgentBacklog backlog;
  public SourceChangesController(AgentService agents, Store store, ConfigurationService config) {
    this.agents = agents;
    this.changes = new SourceChanges(store, config);
    this.backlog = new AgentBacklog(store, config);
  }
  public record Progress(ChangeInbox.Batch batch, String phase, String path) {}
  @GetMapping("/api/source-changes")
  public ApiResponse<?> list(@RequestParam(required = false) String jobId) throws Exception {
    return ApiResponse.ok(changes.view(jobId));
  }

  @GetMapping("/api/agents/{id}/backlog")
  public ApiResponse<?> backlog(@PathVariable String id, @RequestParam(defaultValue = "1") int page) throws Exception {
    agents.device(id);
    return ApiResponse.ok(backlog.view(id, page));
  }

  @PostMapping("/agent/v1/changes/{action}")
  public ApiResponse<?> request(@PathVariable String action, HttpServletRequest request) throws Exception {
    byte[] body = request.getInputStream().readNBytes(1048577);
    if (body.length > 1048576) throw new IllegalArgumentException("请求超过 1 MiB");
    String agent = agents.authenticate(request.getHeader("X-Agent-Id"), request.getMethod(),
        request.getRequestURI(), request.getHeader("X-Agent-Time"), request.getHeader("X-Agent-Nonce"),
        request.getHeader("X-Agent-Signature"), body).id;
    String json = new String(body, StandardCharsets.UTF_8);
    if (action.equals("progress")) {
      Progress progress = Json.read(json, Progress.class);
      changes.progress(agent, progress.batch(), progress.phase(), progress.path());
      return ApiResponse.ok(Map.of());
    }
    var batch = Json.read(json, ChangeInbox.Batch.class);
    return switch (action) {
      case "notice" -> ApiResponse.ok(Map.of("acknowledged", changes.receive(agent, batch)));
      case "start" -> ApiResponse.ok(changes.start(agent, batch));
      case "failed" -> { changes.failed(agent, batch); yield ApiResponse.ok(Map.of()); }
      default -> throw new IllegalArgumentException("未知变化通知动作");
    };
  }
}
