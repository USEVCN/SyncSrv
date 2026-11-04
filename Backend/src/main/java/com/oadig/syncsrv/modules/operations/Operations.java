package com.oadig.syncsrv.modules.operations;

import com.oadig.syncsrv.modules.discovery.AgentService;
import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Json;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import com.oadig.syncsrv.shared.web.ApiResponse;

@Component
@RestController
@RequestMapping("/api/operations")
public final class Operations {
  private final Store store;private final AgentService agents;private final TaskManager tasks;
  private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"operations-monitor");t.setDaemon(true);return t;});
  @Value("${syncsrv.audit-anchor.url:}") private String anchorUrl;
  @Value("${syncsrv.audit-anchor.public-key:}") private String anchorKey;
  @Value("${syncsrv.alert-webhook:}") private String webhook;
  @Value("${syncsrv.minimum-free-bytes:1073741824}") private long minimumFree;
  private volatile String anchorStatus="NOT_CONFIGURED",anchorError="",anchorAt="",monitorError="";
  private volatile long freeBytes;private String lastAlert="";private long nextAlert;
  public Operations(Store store,AgentService agents,TaskManager tasks){this.store=store;this.agents=agents;this.tasks=tasks;}
  @PostConstruct public void start(){timer.scheduleWithFixedDelay(this::tick,1,30,TimeUnit.SECONDS);}
  @PreDestroy public void close(){timer.shutdownNow();}
  private void tick(){
    List<String> issues=new ArrayList<>();
    try {
      freeBytes=Files.getFileStore(store.directory).getUsableSpace();
      if(freeBytes<minimumFree)issues.add("中心可用空间不足");
      long offline=agents.devices().stream().filter(d->"APPROVED".equals(d.status)&&!d.lastSeen.isBlank()&&Instant.parse(d.lastSeen).isBefore(Instant.now().minusSeconds(180))).count();
      if(offline>0)issues.add(offline+" 台备份设备离线");
      var latest=new java.util.HashMap<String,com.oadig.syncsrv.shared.Models.Run>();
      for(var run:tasks.runs())latest.putIfAbsent(run.jobId+"/"+run.kind,run);
      long failed=latest.values().stream().filter(run->"FAILED".equals(run.status)).count();
      if(failed>0)issues.add("最近执行记录中有 "+failed+" 次失败");
      if(!anchorUrl.isBlank()){
        try{new AuditAnchor(store,anchorUrl,anchorKey).checkpoint();anchorStatus="VERIFIED";anchorAt=Instant.now().toString();anchorError="";}
        catch(Exception e){if(e instanceof SecurityException)store.quarantine("外部审计锚点检测到历史回退或分叉，停止写入，需人工核验");anchorStatus="FAILED";anchorError=e.getMessage();issues.add("外部审计锚点异常");}
      }
      String text=String.join("；",issues);
      if(!webhook.isBlank()&&!text.isBlank()&&(!text.equals(lastAlert)||System.currentTimeMillis()>nextAlert)){
        URI uri=URI.create(webhook);if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null)throw new IllegalArgumentException("告警地址必须为 HTTPS");
        var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build().send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("service","SyncSrv","severity","warning","message",text,"time",Instant.now().toString())))).build(),HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()/100!=2)throw new IllegalStateException("Webhook HTTP "+response.statusCode());
        lastAlert=text;nextAlert=System.currentTimeMillis()+600000;
      }
      monitorError="";
    }catch(Exception e){monitorError=e.getMessage();}
  }
  @GetMapping public ApiResponse<?> status()throws Exception{var value=new LinkedHashMap<String,Object>(Map.of("auditAnchor",anchorStatus,"anchorError",anchorError,"lastAnchor",anchorAt,"freeBytes",freeBytes,"minimumFreeBytes",minimumFree,"alertConfigured",!webhook.isBlank(),"monitorError",monitorError,"schemaMin",1,"schemaMax",1,"upgradeMode","SIGNED_MANUAL"));value.put("recoveryPending",store.get("recovery-state","pending",Map.class)!=null);return ApiResponse.ok(value);}
}
