package com.oadig.syncsrv.modules.discovery;

import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Json;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public final class DeviceExecutionController {
  private final AgentService agents;
  private final DeviceExecution executions;
  public DeviceExecutionController(Store store, AgentService agents) {
    this.agents = agents; this.executions = new DeviceExecution(store);
  }
  @PostMapping({"/agent/v1/storage/poll", "/agent/v1/storage/report"})
  public ApiResponse<?> request(HttpServletRequest request) throws Exception {
    byte[] body = request.getInputStream().readNBytes(65537);
    if (body.length > 65536) throw new IllegalArgumentException("执行请求过大");
    var agent = agents.authenticate(request.getHeader("X-Agent-Id"), "POST", request.getRequestURI(),
        request.getHeader("X-Agent-Time"), request.getHeader("X-Agent-Nonce"),
        request.getHeader("X-Agent-Signature"), body);
    String json = new String(body, StandardCharsets.UTF_8);
    if (request.getRequestURI().endsWith("/report")) {
      boolean proceed = executions.report(agent.id, Json.read(json, DeviceExecution.Report.class));
      return ApiResponse.ok(Map.of("proceed", proceed));
    }
    DeviceExecution.Poll poll = Json.read(json, DeviceExecution.Poll.class);
    if (poll.requestId() == null || !poll.requestId().matches("[a-f0-9]{32}")
        || poll.publicKey() == null || poll.publicKey().length() > 256)
      throw new IllegalArgumentException("领取请求不正确");
    DeviceExecution.Command command = executions.await(agent.id, DeviceExecution.activeIds(poll.activeIds()));
    Map<String, Object> payload = command == null ? new java.util.LinkedHashMap<>()
        : Json.MAPPER.convertValue(executions.work(command),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    // Old agents ignore this additive field. New agents only send activeIds after seeing it.
    payload.put("parallelPolling", true);
    return ApiResponse.ok(DirectUploadService.seal(payload, poll.requestId(), poll.publicKey()));
  }
}
