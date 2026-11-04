package com.oadig.syncsrv.modules.operations;
import static com.oadig.syncsrv.platform.persistence.Store.Change.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import com.oadig.syncsrv.modules.discovery.*;
import java.util.*;
/** Restored metadata cannot blindly trust newer receipts cached by still-running devices. */
public final class RecoveryState {
 private RecoveryState(){}
 public static void prepare(Store store,String actor)throws Exception{
  List<Store.Change> changes=new ArrayList<>();
  for(Job job:store.list("job",Job.class)){
   job.enabled=false;changes.add(put("job",job.id,job));
   if("AGENT".equals(job.sourceMode))changes.add(remove("agent-source-state",job.id));
  }
  for(AgentService.Device device:store.list("agent",AgentService.Device.class)){
   device.configVersion++;device.appliedVersion=0;device.lastSeen="";changes.add(put("agent",device.id,device));
  }
  for(DeviceExecution.Command pending:store.list("device-pending",DeviceExecution.Command.class)){
   pending.status="NOT_STARTED";pending.error="灾备恢复后重新校准，原设备回执不再作为完成依据";
   changes.add(put(DeviceExecution.KIND,pending.id,pending));changes.add(remove("device-pending",pending.id));
  }
  for(AgentPushService.Session session:store.list("push-session",AgentPushService.Session.class))
   if("UPLOADING".equals(session.status)){session.status="ABANDONED";changes.add(put("push-session",session.id,session));}
  for(var p:store.list("retention-policy",com.oadig.syncsrv.modules.archive.ArchiveRetention.Policy.class))
   changes.add(put("retention-policy",p.jobId(),new com.oadig.syncsrv.modules.archive.ArchiveRetention.Policy(p.jobId(),p.months(),false,false,"",p.lastMonth(),p.planId(),p.lastRun(),"灾备后需重新确认策略",p.revision()+1)));
  changes.add(put("recovery-state","pending",Map.of("time",Ids.now(),"required","VERIFY_B_C_BEFORE_ENABLING_JOBS")));
  store.commit(actor,"state.recovery_prepared","state",Map.of("jobsPaused",true,"forceFullCalibration",true),changes.toArray(Store.Change[]::new));
 }
}
