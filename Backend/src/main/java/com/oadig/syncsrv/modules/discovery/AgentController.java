package com.oadig.syncsrv.modules.discovery;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Json;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class AgentController {
  private final AgentService agents;
  private final ConfigurationService config;
  private final ChangeInbox inbox;
  private final SyncEngine sync;
  private final Store store;
  private final com.oadig.syncsrv.modules.jobs.TaskManager tasks;

  public AgentController(
      AgentService agents,
      ConfigurationService config,
      Store store,
      SyncEngine sync,
      com.oadig.syncsrv.modules.jobs.TaskManager tasks) {
    this.store = store;
    this.tasks = tasks;
    this.agents = agents;
    this.config = config;
    this.inbox = new ChangeInbox(store);
    this.sync = sync;
  }

  @GetMapping("/api/agents")
  public ApiResponse<?> list() throws Exception {
    return ApiResponse.ok(agents.devices());
  }

  @PostMapping("/api/agents/invitations")
  public ApiResponse<?> invite(HttpServletRequest r) throws Exception {
    return ApiResponse.ok(agents.invitation((String) r.getAttribute("actor")));
  }

  public record Authorization(boolean approved) {}

  @PostMapping("/api/agents/{id}/authorization")
  public ApiResponse<?> authorize(
      @PathVariable String id, @RequestBody Authorization auth, HttpServletRequest r)
      throws Exception {
    return ApiResponse.ok(agents.authorize(id, auth.approved(), (String) r.getAttribute("actor")));
  }

  @GetMapping("/api/discovery")
  public ApiResponse<?> discovery() throws Exception {
    List<Map<String, Object>> out = new ArrayList<>();
    for (var job : config.jobs()) {
      var pending = inbox.pending(job.id);
      var row = new LinkedHashMap<String, Object>();
      row.put("jobId", job.id);
      row.put("mode", job.discoveryMode);
      row.put("pendingCount", pending.size());
      row.put("pending", pending.stream().limit(100).toList());
      row.put("checkpoint", sync.checkpoint(job.id));
      row.put("oldestPendingAt", pending.isEmpty() ? "" : pending.get(0).firstSeen());
      out.add(row);
    }
    return ApiResponse.ok(out);
  }

  @GetMapping("/api/jobs/{id}/directories")
  public ApiResponse<?> directories(@PathVariable String id) throws Exception {
    config.job(id);
    return ApiResponse.ok(new com.oadig.syncsrv.modules.sync.DirectoryIndex(store).list(id));
  }

  @PostMapping("/agent/v1/enroll")
  public ApiResponse<?> enroll(HttpServletRequest r) throws Exception {
    return ApiResponse.ok(
        agents.enroll(
            Json.read(
                new String(body(r), java.nio.charset.StandardCharsets.UTF_8),
                AgentService.Enrollment.class)));
  }

  @RequestMapping(
      value = {"/agent/v1/config", "/agent/v1/heartbeat", "/agent/v1/events", "/agent/v1/bandwidth"},
      method = {RequestMethod.GET, RequestMethod.POST})
  public ApiResponse<?> request(HttpServletRequest r) throws Exception {
    byte[] body = body(r);
    var device =
        agents.authenticate(
            r.getHeader("X-Agent-Id"),
            r.getMethod(),
            r.getRequestURI(),
            r.getHeader("X-Agent-Time"),
            r.getHeader("X-Agent-Nonce"),
            r.getHeader("X-Agent-Signature"),
            body);
    String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
    return switch (r.getRequestURI()) {
      case "/agent/v1/config" -> {
        if (!r.getMethod().equals("GET")) throw new IllegalArgumentException("配置请求须使用 GET");
        yield ApiResponse.ok(agents.configuration(device.id));
      }
      case "/agent/v1/bandwidth" -> {
        if (!r.getMethod().equals("POST")) throw new IllegalArgumentException("限速预约须使用 POST");
        int count=Json.MAPPER.readTree(text).path("bytes").asInt();
        yield ApiResponse.ok(tasks.bandwidth(count));
      }
      case "/agent/v1/heartbeat" -> {
        if (!r.getMethod().equals("POST")) throw new IllegalArgumentException("心跳须使用 POST");
        yield ApiResponse.ok(
            agents.heartbeat(device.id, Json.read(text, AgentService.Heartbeat.class)));
      }
      default -> {
        if (!r.getMethod().equals("POST")) throw new IllegalArgumentException("事件请求须使用 POST");
        var acknowledged = agents.receive(device.id, Json.read(text, ChangeInbox.Batch.class));
        tasks.changesArrived();
        yield ApiResponse.ok(Map.of("acknowledged", acknowledged));
      }
    };
  }

  private static byte[] body(HttpServletRequest r) throws Exception {
    if (r.getContentLengthLong() > 1048576) throw new IllegalArgumentException("请求超过 1 MiB");
    byte[] bytes = r.getInputStream().readNBytes(1048577);
    if (bytes.length > 1048576) throw new IllegalArgumentException("请求超过 1 MiB");
    return bytes;
  }
}
