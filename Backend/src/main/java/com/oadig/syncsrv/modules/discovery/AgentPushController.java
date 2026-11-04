package com.oadig.syncsrv.modules.discovery;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Json;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.*;

@RestController
public final class AgentPushController {
  private final AgentService agents;
  private final AgentPushService push;
  private final DirectUploadService direct;
  private final SourceTransferProgress progress;
  private final ConfigurationService config;
  private final SourceChanges changes;

  public AgentPushController(AgentService agents, Store store, ConfigurationService config) {
    this.agents = agents;
    this.config = config;
    changes = new SourceChanges(store, config);
    push = new AgentPushService(store, config);
    progress = new SourceTransferProgress(store);
    direct = new DirectUploadService(store, config, push);
  }

  @GetMapping("/api/source-transfers")
  public ApiResponse<?> transfers() {return ApiResponse.ok(progress.list());}
  @GetMapping("/api/source-transfers/{id}/files")
  public ApiResponse<?> files(@PathVariable String id,@RequestParam(defaultValue="1") int page) throws Exception {
    return ApiResponse.ok(progress.files(id,page));
  }
  private void observe(Observation action) {
    try {action.run();} catch(Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Source progress observation failed",e);
    }
  }
  @FunctionalInterface private interface Observation {void run() throws Exception;}
  private record Finish(String sessionId, SourceTransferProgress.Stats stats, SourceTransferProgress.Update telemetry) {}

  @PostMapping("/agent/v1/push/{action}")
  public ApiResponse<?> request(@PathVariable String action, HttpServletRequest r)
      throws Exception {
    byte[] bytes = body(r);
    String agent = authenticate(r, bytes);
    String json = new String(bytes, StandardCharsets.UTF_8);
    Object result =
        switch (action) {
          case "direct-prepare-batch" -> {
            var input=Json.read(json,DirectUploadService.BatchRequest.class);
            var tickets=direct.prepareBatch(agent,input);
            observe(()->{for(int i=0;i<tickets.size();i++)if(tickets.get(i).ready())progress.complete(input.sessionId(),input.entries().get(i));});
            yield tickets;
          }
          case "direct-complete-batch" -> {
            var input=Json.read(json,DirectUploadService.BatchRequest.class);
            var tickets=direct.completeBatch(agent,input);
            observe(()->{for(var n:input.entries())progress.complete(input.sessionId(),n);});
            yield tickets;
          }
          case "direct-open" -> direct.open(agent, Json.read(json, DirectUploadService.Open.class));
          case "direct-prepare" -> {
            var input=Json.read(json,DirectUploadService.FileRequest.class);
            var ticket=direct.prepare(agent,input);
            observe(()->progress.prepared(input.sessionId(),input.entry(),ticket.ready()));yield ticket;
          }
          case "direct-complete" -> {
            var input=Json.read(json,DirectUploadService.FileRequest.class);
            var ticket=direct.complete(agent,input);
            observe(()->progress.complete(input.sessionId(),input.entry()));yield ticket;
          }
          case "progress" -> {
            progress.update(agent,Json.read(json,SourceTransferProgress.Update.class));yield java.util.Map.of();
          }
          case "begin" -> {
            var begin = Json.read(json, AgentPushService.Begin.class);
            if (!"DIRECT".equals(begin.transferMode())) throw new IllegalArgumentException("请升级源端：源目录必须直接连接备份库，服务端不接收 源目录的文件数据");
            var session=push.begin(agent, begin);
            observe(()->progress.begin(session));yield session;
          }
          case "inventory" -> { progress.inventory(agent,Json.read(json,AgentPushService.Page.class));yield java.util.Map.of(); }
          case "page" -> {
            var input=Json.read(json,AgentPushService.Page.class);var session=push.page(agent,input);
            observe(()->progress.page(session,input.entries()));yield session;
          }
          case "prepare" -> throw new IllegalArgumentException("请使用源端直连备份库的传输方式");
          case "abort" -> {
            String id=Json.read(json,Finish.class).sessionId();var receipt=push.abort(agent,id);
            boolean revoked=!agents.device(agent).status.equals("APPROVED");
            boolean paused=!config.job(receipt.jobId).enabled || revoked;
            if(paused && !"PUBLISHED".equals(receipt.status))changes.paused(agent,receipt.jobId,revoked);
            observe(()->progress.finish(id,"PUBLISHED".equals(receipt.status) ? "COMPLETED" : paused ? "PAUSED" : "FAILED"));yield receipt;
          }
          case "finish" -> {
            var input=Json.read(json,Finish.class);String id=input.sessionId();
            if(input.telemetry()!=null && id.equals(input.telemetry().sessionId()))observe(()->progress.update(agent,input.telemetry()));
            long started=System.nanoTime();var receipt=push.finish(agent,id);
            long elapsed=(System.nanoTime()-started)/1_000_000;
            observe(()->{progress.statistics(id,input.stats(),elapsed);progress.finish(id,true);});yield receipt;
          }
          default -> throw new IllegalArgumentException("未知上传动作");
        };
    return ApiResponse.ok(result);
  }

  @PostMapping("/agent/v1/push/chunk/{job}/{hash}/{offset}")
  public ApiResponse<?> chunk(
      @PathVariable String job,
      @PathVariable String hash,
      @PathVariable long offset,
      HttpServletRequest r)
      throws Exception {
    byte[] bytes = body(r);
    authenticate(r, bytes);
    throw new IllegalArgumentException("服务端不接收 源目录的文件数据，请使用源端直连备份库");
  }

  private String authenticate(HttpServletRequest r, byte[] bytes) throws Exception {
    return agents.authenticate(
            r.getHeader("X-Agent-Id"),
            r.getMethod(),
            r.getRequestURI(),
            r.getHeader("X-Agent-Time"),
            r.getHeader("X-Agent-Nonce"),
            r.getHeader("X-Agent-Signature"),
            bytes)
        .id;
  }

  private byte[] body(HttpServletRequest r) throws Exception {
    byte[] bytes = r.getInputStream().readNBytes(1048577);
    if (bytes.length > 1048576) throw new IllegalArgumentException("请求超过 1 MiB");
    return bytes;
  }
}
