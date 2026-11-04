package com.oadig.syncsrv.modules.archive;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Ids;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.web.bind.annotation.*;

/** Opt-in monthly policy. Preview-only is the default; execution uses the normal audited queue. */
@RestController
@RequestMapping("/api/jobs/{jobId}/archive/retention")
public final class ArchiveRetention {
  public record Policy(String jobId,int months,boolean enabled,boolean autoExecute,String confirmation,
      String lastMonth,String planId,String lastRun,String error,long revision) {}
  private final Store store;private final ArchiveService archive;private final TaskManager tasks;private final ConfigurationService config;
  private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"archive-retention");t.setDaemon(true);return t;});
  public ArchiveRetention(Store store,ConfigurationService config,TaskManager tasks){this.store=store;this.config=config;this.archive=new ArchiveService(store,config);this.tasks=tasks;}
  @PostConstruct public void start(){timer.scheduleWithFixedDelay(this::tick,60,600,TimeUnit.SECONDS);}
  @PreDestroy public void close(){timer.shutdownNow();}
  @GetMapping public ApiResponse<?> get(@PathVariable String jobId)throws Exception{config.job(jobId);Policy p=store.get("retention-policy",jobId,Policy.class);return ApiResponse.ok(p==null?new Policy(jobId,12,false,false,"","","","","",0):p);}
  @PutMapping public synchronized ApiResponse<?> save(@PathVariable String jobId,@RequestBody Policy request,HttpServletRequest r)throws Exception{
    config.job(jobId);Policy previous=store.get("retention-policy",jobId,Policy.class);
    if(request.months()<1||request.months()>1200)throw new IllegalArgumentException("保留月数为1至1200");
    if(request.autoExecute()&&!"自动清理C的过期历史版本".equals(request.confirmation()))throw new IllegalArgumentException("自动执行需明确确认删除过期历史版本");
    if(request.revision()!=(previous==null?0:previous.revision()))throw new IllegalArgumentException("策略已被修改，请刷新");
    Policy policy=new Policy(jobId,request.months(),request.enabled(),request.autoExecute(),"", "","","","",request.revision()+1);
    store.commit((String)r.getAttribute("actor"),"archive.retention_saved",jobId,policy,put("retention-policy",jobId,policy));return ApiResponse.ok(policy);
  }
  private synchronized void tick(){
    try{if(store.get("recovery-state","pending",Map.class)!=null)return;for(Policy p:store.list("retention-policy",Policy.class)){
      String month=YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();
      if(!p.enabled()||month.equals(p.lastMonth()))continue;
      try{
        var plan=archive.preview(p.jobId(),new ArchiveModels.Request(YearMonth.parse(month).minusMonths(p.months()).toString(),List.of()),"retention");
        String run="";
        if(p.autoExecute()&&(!plan.items.isEmpty()||!plan.expired.isEmpty()))run=tasks.archive(p.jobId(),plan.id,true,"retention").id;
        Policy done=new Policy(p.jobId(),p.months(),true,p.autoExecute(),"",month,plan.id,run,"",p.revision()+1);
        store.commit("retention","archive.retention_prepared",p.jobId(),done,put("retention-policy",p.jobId(),done));
      }catch(Exception e){
        Policy failed=new Policy(p.jobId(),p.months(),true,p.autoExecute(),"",month,"","",e.getMessage(),p.revision()+1);
        store.commit("retention","archive.retention_failed",p.jobId(),Map.of("error",e.getMessage()),put("retention-policy",p.jobId(),failed));
      }
    }}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Retention scheduling failed: {}",e.getMessage());}
  }
}
