package com.oadig.syncsrv.modules.discovery;

import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.modules.transfer.RunProgress;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.util.*;
import static com.oadig.syncsrv.platform.persistence.Store.Change.put;

/** Metadata-only observation of A -> B; never used as a backup receipt or commit authority. */
public final class SourceTransferProgress {
  public record Stats(long initializationMillis,long transferMillis,long finalizationMillis,
      int concurrency,int maxConcurrency,boolean automatic,String reason) {}
  private Stats safe(Stats s,long finalizationMillis) {
    if(s==null)return null;
    return new Stats(Math.max(0,s.initializationMillis()),Math.max(0,s.transferMillis()),
        Math.max(0,finalizationMillis),Math.max(0,Math.min(16,s.concurrency())),
        Math.max(0,Math.min(16,s.maxConcurrency())),s.automatic(),
        s.reason()==null ? "" : s.reason().substring(0,Math.min(200,s.reason().length())));
  }
  public synchronized void statistics(String id,Stats stats,long finalizationMillis) {
    Live live=sessions.get(id);if(live!=null && stats!=null) {
      live.run.sourceTransfer=safe(stats,finalizationMillis);
      live.progress.concurrency(live.run.sourceTransfer.concurrency());
    }
  }
  public record FileUpdate(String path,String phase,long offset,long phaseBytes) {}
  public record Update(String sessionId, String path, String phase, long offset,
      long transferred, long verified, long sequence,List<FileUpdate> files,Stats stats) {
    public Update(String id,String path,String phase,long offset,long transferred,long verified,long sequence,List<FileUpdate> files){this(id,path,phase,offset,transferred,verified,sequence,files,null);}
    public Update(String id,String path,String phase,long offset,long transferred,long verified,long sequence){this(id,path,phase,offset,transferred,verified,sequence,null,null);}
  }
  public record View(Run run, boolean telemetry) {}
  private static final class Live {
    final Run run=new Run();
    final RunProgress progress=new RunProgress();
    final Map<String,Long> sizes=new HashMap<>();
    final Set<String> completed=new HashSet<>();
    final Map<Integer,String> inventoryReceipts=new HashMap<>();
    long transferred,sequence;int inventoryCount;
    String sampledAt="1970-01-01T00:00:00Z";
    boolean telemetry, saved;
  }
  private final Store store;
  private final Map<String,Live> sessions=new LinkedHashMap<>();
  public SourceTransferProgress(Store store) {this.store=store;}
  public synchronized void begin(AgentPushService.Session session) throws Exception {
    // A retried begin must never make already published work RUNNING again.
    if (!"UPLOADING".equals(session.status)) return;
    if(sessions.containsKey(session.id)) return;
    // Retain only one observation per job and cap idle histories in memory.
    sessions.values().removeIf(v->v.run.jobId.equals(session.jobId));
    while(sessions.size()>=128) sessions.remove(sessions.keySet().iterator().next());
    Live live=new Live();Run r=live.run;r.id=session.id;r.jobId=session.jobId;
    r.kind="SOURCE_UPLOAD";r.status="RUNNING";r.phase="CONNECTING_B";
    r.startedAt=session.createdAt;r.actualStartedAt=session.createdAt;r.trigger="EVENTS";
    live.progress.start();sessions.put(session.id,live);
    // After a center restart, recover the durable hashed portion of the file plan.
    var entries=store.list(AgentPushService.draftKind(session.id),Node.class);
    page(session,entries);
    if(session.direct && !entries.isEmpty()) {
      var receipts=store.getMany(DirectUploadService.kind(session.jobId),
          entries.stream().filter(n->!n.directory()).map(DirectUploadService::key).toList(),DirectUploadService.Content.class);
      for(Node n:entries) {
        var receipt=receipts.get(DirectUploadService.key(n));
        if(!n.directory() && receipt!=null && !receipt.verifiedAt().isBlank()
            && com.oadig.syncsrv.modules.sync.TreeScanner.sameContent(receipt.entry(),n))complete(session.id,n);
      }
    }
  }
  public synchronized void page(AgentPushService.Session session,List<Node> entries) {
    Live live=sessions.get(session.id);if(live==null)return;
    for(Node n:entries) if(!n.directory() && live.sizes.putIfAbsent(n.path(),n.size())==null)
      live.progress.plan(n.path(),"WRITE",n.size());
    if(session.received==session.total) live.progress.planned();
  }
  public synchronized void inventory(String agent,AgentPushService.Page input)throws Exception {
    if(input==null)throw new IllegalArgumentException("发现清单不能为空");
    var session=store.get("push-session",input.sessionId(),AgentPushService.Session.class);
    if(session==null || !session.agentId.equals(agent))throw new SecurityException("设备无此会话权限");
    begin(session);
    Live live=sessions.get(session.id);if(live==null || !session.status.equals("UPLOADING"))throw new IllegalArgumentException("清单会话已结束");
    if(input.entries()==null || input.entries().isEmpty() || input.entries().size()>256 || input.offset()<0)throw new IllegalArgumentException("发现清单分页不正确");
    Set<String> paths=new HashSet<>();
    for(Node n:input.entries()){
      if(n==null || n.path()==null || !paths.add(n.path()))throw new IllegalArgumentException("发现清单包含重复或空条目");
      com.oadig.syncsrv.platform.storage.StoragePaths.relative(n.path());
      if(n.path().isEmpty() || n.path().equals(".") || n.path().equalsIgnoreCase(".syncsrv")
          || n.path().toLowerCase(Locale.ROOT).startsWith(".syncsrv/") || n.path().length()>4096 || n.size()<0
          || n.directory() && n.size()!=0 || !(com.oadig.syncsrv.modules.sync.SyncEngine.within(n.path(),session.scopes) || n.directory() && session.scopes.stream().anyMatch(p->p.startsWith(n.path()+"/"))))throw new IllegalArgumentException("发现清单超出范围");
    }
    String fingerprint=Ids.hash(Json.bytes(input.entries()));
    String old=live.inventoryReceipts.get(input.offset());
    if(old!=null){if(!old.equals(fingerprint))throw new IllegalArgumentException("同一发现分页不能改变内容");return;}
    if(input.offset()!=live.inventoryCount || input.entries().size()>session.total-live.inventoryCount)throw new IllegalArgumentException("发现清单分页不正确");
    page(session,input.entries());live.inventoryCount+=input.entries().size();
    live.inventoryReceipts.put(input.offset(),fingerprint);
    if(live.inventoryCount==session.total)live.progress.planned();
    live.run.phase="HASHING_SOURCE";
  }
  public synchronized void prepared(String id,Node n,boolean ready) {
    Live live=sessions.get(id);if(live==null || live.completed.contains(n.path()))return;
    if(!live.progress.select(n.path())) live.progress.begin(n.path(),"WRITE",n.size());
    live.run.currentPath=n.path();live.run.phase="UPLOADING_B";live.run.phaseBytes=0;live.run.phaseTotal=0;
    live.progress.stage("UPLOADING_B");
    if(ready) complete(id,n);
  }
  public synchronized void complete(String id,Node n) {
    Live live=sessions.get(id);if(live==null || !live.completed.add(n.path()))return;
    if(!live.progress.select(n.path())) live.progress.begin(n.path(),"WRITE",n.size());
    live.progress.finishFile("COMPLETED","");live.run.currentPath="";
  }
  public synchronized void update(String agent,Update u) throws Exception {
    if(u==null)throw new IllegalArgumentException("进度不能为空");
    var session=store.get("push-session",u.sessionId(),AgentPushService.Session.class);
    if(session==null || !session.agentId.equals(agent)) throw new SecurityException("设备无此会话权限");
    begin(session);
    Live live=sessions.get(u.sessionId());
    if(live==null || !session.status.equals("UPLOADING") || !live.run.status.equals("RUNNING"))return;
    if(u.sequence()<=live.sequence)return;
    if(u.transferred()<live.transferred || u.verified()<live.run.verifiedBytes || u.offset()<0)
      throw new IllegalArgumentException("进度计数不能倒退");
    if(u.phase()==null || !Set.of("CONNECTING_B","HASHING_SOURCE","UPLOADING_B","VERIFYING_B","WAITING_COMMIT").contains(u.phase()))
      throw new IllegalArgumentException("未知源端传输阶段");
    List<FileUpdate> files=u.files()==null ? (u.path()==null || u.path().isEmpty() ? List.of() : List.of(new FileUpdate(u.path(),u.phase(),u.offset(),0))) : u.files();
    if(files.size()>16)throw new IllegalArgumentException("并发进度超过上限");
    for(FileUpdate f:files){
      if(f==null || f.path()==null || f.phase()==null || f.offset()<0 || f.phaseBytes()<0 || !Set.of("HASHING_SOURCE","UPLOADING_B","VERIFYING_B").contains(f.phase()))throw new IllegalArgumentException("文件进度不正确");
      if(live.completed.contains(f.path()) || !live.sizes.containsKey(f.path()))continue;
      if(!live.progress.select(f.path()))live.progress.begin(f.path(),"WRITE",live.sizes.get(f.path()));
      live.progress.stage(f.phase());
      live.progress.beginTransfer(f.phase().equals("HASHING_SOURCE") ? 0 : Math.min(f.offset(),live.sizes.get(f.path())),"UPLOADING_B");
      live.progress.phaseProgress(f.phaseBytes(),f.phase().equals("UPLOADING_B") ? 0 : live.sizes.get(f.path()));
    }
    if(u.stats()!=null)statistics(u.sessionId(),u.stats(),0);
    live.run.phase=u.phase();
    live.progress.observeTransferred(u.transferred()-live.transferred);
    live.transferred=u.transferred();live.run.bytes=u.transferred();live.run.verifiedBytes=u.verified();
    live.sequence=u.sequence();live.telemetry=true;live.sampledAt=Ids.now();
    live.progress.snapshot(live.run); // Sample even when nobody has the webpage open.
  }
  public synchronized void finish(String id,boolean success) throws Exception {
    finish(id,success ? "COMPLETED" : "FAILED");
  }
  public synchronized void finish(String id,String status) throws Exception {
    if(!Set.of("COMPLETED","FAILED","PAUSED").contains(status))throw new IllegalArgumentException("源端终态不正确");
    Live live=sessions.get(id);if(live==null)return;
    if(live.saved)return;
    if(live.run.status.equals("RUNNING")) {
    boolean success=status.equals("COMPLETED");
    live.run.status=status;
    live.run.phase=success ? "WAITING_COMMIT" : live.run.phase;
    live.run.completedAt=Ids.now();live.run.error=success ? "" : status.equals("PAUSED") ? "源端传输已暂停，变化保留等待恢复" : "源端传输未完成，等待重试";
    for(var file:live.sizes.entrySet())if(!live.completed.contains(file.getKey())) {
      if(success)complete(id,new Node(file.getKey(),false,file.getValue(),0,"",""));
      else {
        if(!live.progress.select(file.getKey()))live.progress.begin(file.getKey(),"WRITE",file.getValue());
        live.progress.finishFile(status,live.run.error);
      }
    }
    if(live.sizes.isEmpty())live.progress.planned();
    live.progress.finish(live.run);
    }
    var pages=live.progress.pending(live.run);
    List<Store.Change> changes=new ArrayList<>();
    changes.add(put("source-transfer",id,view(live)));
    for(var page:pages) changes.add(put("source-file-page",RunProgress.pageId(id,page.number()),page.page()));
    store.commit("system","source.progress_saved",id,Map.of("session",id),changes.toArray(Store.Change[]::new));
    live.progress.saved(pages);
    live.saved=true;
  }
  private View view(Live live) {
    Run r=Json.read(Json.write(live.run),Run.class);
    var p=live.progress.snapshot(r);
    r.progress=new RunProgress.Summary(p.planComplete(),p.processedBytes(),p.totalBytes(),p.processedFiles(),
        p.totalFiles(),p.entries(),p.elapsedMillis(),p.estimatedTotalMillis(),p.transferBytesPerSecond(),
        p.verificationBytesPerSecond(),live.sampledAt,p.activeFiles(),p.peakActiveFiles(),p.concurrencyLimit(),p.activeChecks(),p.peakChecks());
    return new View(r,live.telemetry);
  }
  public synchronized List<View> list() {
    return sessions.values().stream().map(this::view).toList();
  }
  public synchronized TaskManager.Files files(String id,int page) throws Exception {
    if(page<1 || page>1_000_000)throw new IllegalArgumentException("文件列表页码不正确");
    Live live=sessions.get(id);
    if(live!=null) {
      Run r=view(live).run();
      return new TaskManager.Files(r,"SOURCE",live.progress.page(page-1,live.run).entries(),r.progress.entries(),page,RunProgress.PAGE_SIZE);
    }
    View saved=store.get("source-transfer",id,View.class);
    if(saved==null)throw new IllegalArgumentException("源端文件列表尚未采集，请升级源端后重新执行");
    var data=store.get("source-file-page",RunProgress.pageId(id,page-1),RunProgress.Page.class);
    return new TaskManager.Files(saved.run(),"SOURCE",data==null ? List.of() : data.entries(),saved.run().progress.entries(),page,RunProgress.PAGE_SIZE);
  }
}
