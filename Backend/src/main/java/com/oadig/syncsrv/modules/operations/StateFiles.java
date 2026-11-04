package com.oadig.syncsrv.modules.operations;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.modules.sync.TreeScanner;
import com.oadig.syncsrv.modules.jobs.RunReports;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;

/** Offline read-only verification of every retained recovery point and the current B tree. */
public final class StateFiles {
 private StateFiles(){}
 public record FileRef(String endpoint,String path,long size,String hash){}
 public static void command()throws Exception{
  var env=new StandardEnvironment();ConfigDataEnvironmentPostProcessor.applyTo(env);
  try(Store store=new Store(Path.of(env.getRequiredProperty("syncsrv.data")))){
   verify(store,"os:"+Objects.toString(System.getenv("SUDO_USER"),System.getProperty("user.name")));
  }
 }
 public static void verify(Store store,String actor)throws Exception{
  var config=new ConfigurationService(store);var transfer=new TransferService(store);var sync=new SyncEngine(store,config,transfer);
  Map<String,FileRef> files=new LinkedHashMap<>();Map<String,Node> expected=new HashMap<>();
  Run run=new Run();run.id=Ids.next();run.kind="STATE_VERIFY";run.jobId="系统运维";run.actor=actor;run.startedAt=Ids.now();run.actualStartedAt=run.startedAt;run.queuedAt=run.startedAt;run.status="RUNNING";
  TaskContext task=new TaskContext(run,new BandwidthLimiter(0),0);task.metrics.start();
  Map<String,Storage> opened=new HashMap<>();
  try{
   for(Snapshot snapshot:store.list("snapshot",Snapshot.class)){
    Snapshot checked=sync.snapshot(snapshot.id());
    for(Node node:checked.entries())if(!node.directory())reference(expected,node);
    byte[] bytes=Json.bytes(checked);Job job=config.job(checked.jobId());
    add(files,new FileRef(job.archiveId,job.id+"/snapshots/"+checked.id()+".json",bytes.length,Ids.hash(bytes)));
   }
   for(Job job:config.jobs()){
    for(String endpoint:List.of(job.targetId,job.archiveId)){
      if(!opened.containsKey(endpoint))opened.put(endpoint,StorageFactory.open(config.endpoint(endpoint)));
      var root=opened.get(endpoint).stat("");if(root==null||!root.directory()||root.symbolic())throw new IOException("B/C 根目录不可访问");
    }
    var current=sync.current(job.id);var actual=new TreeScanner(transfer).scan(opened.get(job.targetId),task,true,false);
    if(!current.keySet().equals(actual.keySet()))throw new IOException("B 包含未知或缺失条目，请恢复匹配的文件卷快照");
    for(Node node:current.values()){
      Node found=actual.get(node.path());if(found.directory()!=node.directory()||found.size()!=node.size())throw new IOException("B 文件类型或大小不符: "+node.path());
      if(!node.directory())reference(expected,node);
    }
   }
   var versions=store.getMany("version",expected.keySet(),Version.class);
   for(String id:expected.keySet()){Version version=versions.get(id);if(version==null)throw new IOException("恢复引用的版本不存在: "+id);if(version.size()!=expected.get(id).size()||!version.hash().equals(expected.get(id).hash()))throw new IOException("恢复清单与版本身份不符: "+id);add(files,new FileRef(version.endpointId(),version.location(),version.size(),version.hash()));}
   run.total=files.size();
   for(FileRef file:files.values())task.progress.plan(file.endpoint()+":"+file.path(),"VERIFY",file.size());task.progress.planned();
   for(FileRef file:files.values()){
    Storage storage=opened.get(file.endpoint());if(storage==null){storage=StorageFactory.open(config.endpoint(file.endpoint()));opened.put(file.endpoint(),storage);}
    Storage.Item item=storage.stat(file.path());
    if(item==null||item.directory()||item.symbolic()||item.size()!=file.size())throw new IOException("恢复文件缺失或大小不符: "+file.path());
    String path=file.endpoint()+":"+file.path();task.stage("VERIFYING",path);task.progress.begin(path,"VERIFY",file.size());
    try(var measured=task.metrics.operation(path,"VERIFY")){
     try{if(!transfer.hash(storage,file.path(),file.size(),task).equals(file.hash()))throw new IOException("恢复文件 SHA-256 不符: "+file.path());}
     catch(Exception e){measured.failed(e,false);throw e;}
    }
    run.done++;task.progress.finishFile("COMPLETED", "完整内容校验通过");
   }
   run.status="COMPLETED";run.phase="VERIFIED";
  }catch(Exception failure){run.status="FAILED";run.error=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName());throw failure;}
  finally{
   for(Storage storage:opened.values())try{storage.close();}catch(Exception ignored){}
   run.completedAt=Ids.now();run.progress=task.progress.snapshot(run);task.metrics.finish();
   var report=RunReports.create(run,null,task.metrics.snapshot(),"MEASURED");List<Store.Change> changes=new ArrayList<>();changes.add(put("run",run.id,run));changes.add(put("run-report",run.id,report));
   for(var page:task.progress.pending(run))changes.add(put("run-file-page",RunProgress.pageId(run.id,page.number()),page.page()));
   if("COMPLETED".equals(run.status))changes.add(remove("recovery-state","pending"));
   store.commit(actor,"COMPLETED".equals(run.status)?"state.files_verified":"state.verification_failed",run.id,Map.of("status",run.status,"reportHash",Ids.hash(Json.bytes(report))),changes.toArray(Store.Change[]::new));
  }
 }
 private static void reference(Map<String,Node> expected,Node node)throws IOException{
  Node old=expected.putIfAbsent(node.versionId(),node);if(old!=null&&(old.size()!=node.size()||!old.hash().equals(node.hash())))throw new IOException("同一版本存在不一致的恢复引用");
 }
 private static void add(Map<String,FileRef> files,FileRef file)throws Exception{
  StoragePaths.relative(file.path());String key=file.endpoint()+"\n"+file.path();FileRef old=files.putIfAbsent(key,file);
  if(old!=null&&(old.size()!=file.size()||!old.hash().equals(file.hash())))throw new IOException("同一存储路径存在冲突的版本引用");
 }
}
