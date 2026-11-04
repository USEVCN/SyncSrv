package com.oadig.syncsrv;

import com.oadig.syncsrv.modules.discovery.*;
import com.oadig.syncsrv.shared.Ids;
import com.oadig.syncsrv.shared.Models.Node;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SourceTransferProgressTest {
  @Test void savesSourceStageTimingsAndConcurrencyWithCompletedRun() throws Exception {
    MemoryStore memory=new MemoryStore();var p=new SourceTransferProgress(memory.store);
    var session=new AgentPushService.Session();session.id="stats";session.agentId="device";
    session.jobId="job";session.createdAt=Ids.now();memory.put("push-session",session.id,session);
    p.begin(session);
    var stats=new SourceTransferProgress.Stats(100,200,0,6,12,true,"完成速度提高");
    p.update("device",new SourceTransferProgress.Update(session.id,"","WAITING_COMMIT",0,30,60,1,List.of(),stats));
    p.statistics(session.id,stats,50);p.finish(session.id,true);
    var saved=new SourceTransferProgress(memory.store).files(session.id,1).run();
    assertEquals(100,saved.sourceTransfer.initializationMillis());
    assertEquals(200,saved.sourceTransfer.transferMillis());
    assertEquals(50,saved.sourceTransfer.finalizationMillis());
    assertEquals(6,saved.sourceTransfer.concurrency());assertTrue(saved.sourceTransfer.automatic());
    assertEquals(30,saved.bytes);assertEquals(60,saved.verifiedBytes);
  }
  @Test void inventoryShowsQueuedFilesAndConcurrentSamplesStayIndependent() throws Exception {
    MemoryStore memory=new MemoryStore();var p=new SourceTransferProgress(memory.store);
    var session=new AgentPushService.Session();session.id=Ids.textHash("parallel");session.agentId="device";
    session.jobId="job";session.createdAt=Ids.now();session.total=2;session.scopes=List.of("");
    memory.put("push-session",session.id,session);p.begin(session);
    Node a=new Node("a",false,100,0,"","");Node b=new Node("b",false,200,0,"","");
    p.inventory("device",new AgentPushService.Page(session.id,0,List.of(a,b)));
    assertTrue(p.list().get(0).run().progress.planComplete());
    assertEquals(300,p.list().get(0).run().progress.totalBytes());
    assertTrue(p.files(session.id,1).entries().stream().allMatch(f->f.status().equals("QUEUED")));
    assertEquals(0,memory.store.get("push-session",session.id,AgentPushService.Session.class).received,
        "metadata inventory must not authorize a hashed manifest");
    p.update("device",new SourceTransferProgress.Update(session.id,"","CONNECTING_B",0,0,0,1));
    p.update("device",new SourceTransferProgress.Update(session.id,"a","UPLOADING_B",40,40,80,2,List.of(
        new SourceTransferProgress.FileUpdate("a","UPLOADING_B",40,0),
        new SourceTransferProgress.FileUpdate("b","HASHING_SOURCE",0,80))));
    var rows=p.files(session.id,1).entries();
    assertEquals(2,rows.stream().filter(f->f.status().equals("RUNNING")).count());
    assertEquals(40,rows.get(0).processedBytes());assertEquals(0,rows.get(1).processedBytes());
    assertEquals(80,rows.get(1).phaseBytes());assertEquals(200,rows.get(1).phaseTotal());
    assertEquals(40,p.list().get(0).run().progress.processedBytes());
    p.complete(session.id,a);
    p.update("device",new SourceTransferProgress.Update(session.id,"b","UPLOADING_B",30,70,200,3,List.of(
        new SourceTransferProgress.FileUpdate("a","UPLOADING_B",50,0),
        new SourceTransferProgress.FileUpdate("b","UPLOADING_B",30,0))));
    assertEquals(130,p.list().get(0).run().progress.processedBytes());
    assertEquals(1,p.list().get(0).run().progress.processedFiles());
    p.finish(session.id,false);
    assertEquals("COMPLETED",p.files(session.id,1).entries().get(0).status());
    assertEquals("FAILED",p.files(session.id,1).entries().get(1).status());
  }
  @Test void uploadedReceiptsDetermineCompletionAndDelayedSamplesCannotReopenAFile() throws Exception {
    MemoryStore memory=new MemoryStore();var p=new SourceTransferProgress(memory.store);
    var session=new AgentPushService.Session();session.id=Ids.textHash("session");session.agentId="device";
    session.jobId="job";session.createdAt=Ids.now();session.total=2;session.received=2;
    memory.put("push-session",session.id,session);
    Node a=new Node("first",false,100,0,Ids.textHash("a"),"");
    Node b=new Node("second",false,200,0,Ids.textHash("b"),"");
    p.begin(session);p.page(session,List.of(a,b));p.prepared(session.id,a,false);
    p.update("device",new SourceTransferProgress.Update(session.id,"first","UPLOADING_B",40,40,0,1));
    assertEquals(40,p.list().get(0).run().progress.processedBytes());
    assertEquals(0,p.list().get(0).run().progress.processedFiles());
    assertThrows(SecurityException.class,()->p.update("intruder",new SourceTransferProgress.Update(session.id,"first","UPLOADING_B",40,40,0,2)));
    p.complete(session.id,a);p.prepared(session.id,b,false);
    p.update("device",new SourceTransferProgress.Update(session.id,"first","VERIFYING_B",100,100,100,2));
    var rows=p.files(session.id,1).entries();
    assertEquals("COMPLETED",rows.get(0).status());assertEquals("RUNNING",rows.get(1).status());
    assertEquals(0,rows.get(1).processedBytes());
    p.update("device",new SourceTransferProgress.Update(session.id,"second","UPLOADING_B",50,150,100,3));
    p.update("device",new SourceTransferProgress.Update(session.id,"second","UPLOADING_B",10,110,100,2));
    assertEquals(150,p.list().get(0).run().progress.processedBytes());
    p.finish(session.id,false);
    assertTrue(memory.objects.get("source-file-page").keySet().stream().allMatch(k->k.length()<=64));
    assertEquals("run:0",com.oadig.syncsrv.modules.transfer.RunProgress.pageId("run",0));
    assertNotEquals(com.oadig.syncsrv.modules.transfer.RunProgress.pageId(session.id,0),
        com.oadig.syncsrv.modules.transfer.RunProgress.pageId(session.id,1));
    var saved=new SourceTransferProgress(memory.store).files(session.id,1);
    assertEquals("FAILED",saved.run().status);assertEquals(150,saved.run().progress.processedBytes());
    assertEquals("FAILED",saved.entries().get(1).status());
    assertEquals(0,saved.run().progress.transferBytesPerSecond());
  }

  @Test void terminalBeginRetryDoesNotReopenPublishedOrAbandonedSession() throws Exception {
    MemoryStore memory=new MemoryStore();var p=new SourceTransferProgress(memory.store);
    var session=new AgentPushService.Session();session.id="terminal";session.agentId="device";session.jobId="job";
    session.createdAt=Ids.now();session.status="PUBLISHED";p.begin(session);
    assertTrue(p.list().isEmpty());session.status="ABANDONED";p.begin(session);assertTrue(p.list().isEmpty());
  }

  @Test void inventoryRetryIsIdempotentAndChangedRetryIsRejected() throws Exception {
    MemoryStore memory=new MemoryStore();var p=new SourceTransferProgress(memory.store);
    var s=new AgentPushService.Session();s.id="inventory";s.agentId="device";s.jobId="job";
    s.createdAt=Ids.now();s.total=2;s.scopes=List.of("");memory.put("push-session",s.id,s);
    var first=new AgentPushService.Page(s.id,0,List.of(new Node("first",false,100,0,"","")));
    p.inventory("device",first);p.inventory("device",first);
    assertEquals(1,p.list().get(0).run().progress.totalFiles());
    assertThrows(IllegalArgumentException.class,()->p.inventory("device",new AgentPushService.Page(s.id,0,List.of(new Node("first",false,101,0,"","")))));
    p.inventory("device",new AgentPushService.Page(s.id,1,List.of(new Node("second",false,200,0,"",""))));
    assertEquals(300,p.list().get(0).run().progress.totalBytes());
    p.finish(s.id,"PAUSED");
    assertTrue(p.files(s.id,1).entries().stream().allMatch(e->e.status().equals("PAUSED")));
    long commits=memory.actions.stream().filter("source.progress_saved"::equals).count();
    p.finish(s.id,false);assertEquals("PAUSED",p.files(s.id,1).run().status);
    assertEquals(commits,memory.actions.stream().filter("source.progress_saved"::equals).count());
  }

  @Test void telemetryAfterCenterRestartReconstructsDraftAndVerifiedFiles() throws Exception {
    MemoryStore memory=new MemoryStore();var s=new AgentPushService.Session();
    s.id="restart";s.agentId="device";s.jobId="job";s.createdAt=Ids.now();s.total=2;s.received=2;s.direct=true;
    memory.put("push-session",s.id,s);Node a=new Node("a",false,100,0,Ids.textHash("a"),"");Node b=new Node("b",false,200,0,Ids.textHash("b"),"");
    String draft=Ids.textHash("push-draft:"+s.id).substring(0,32);
    memory.put(draft,Ids.textHash(a.path()),a);memory.put(draft,Ids.textHash(b.path()),b);
    memory.put(DirectUploadService.kind(s.jobId),DirectUploadService.key(a),new DirectUploadService.Content(DirectUploadService.path(s.jobId,a),a,Ids.now()));
    var p=new SourceTransferProgress(memory.store);
    p.update("device",new SourceTransferProgress.Update(s.id,"b","UPLOADING_B",50,150,100,5));
    assertEquals(2,p.list().get(0).run().progress.totalFiles());assertEquals(150,p.list().get(0).run().progress.processedBytes());
    assertTrue(p.list().get(0).run().progress.planComplete());assertEquals(1,p.list().get(0).run().progress.processedFiles());
  }


  @Test void failedProgressPersistenceCanRetryWithoutChangingTerminalOutcome() throws Exception {
    MemoryStore memory=new MemoryStore();var p=new SourceTransferProgress(memory.store);var s=new AgentPushService.Session();
    s.id="receipt";s.agentId="device";s.jobId="job";s.createdAt=Ids.now();memory.put("push-session",s.id,s);p.begin(s);
    org.mockito.Mockito.doThrow(new java.io.IOException("database temporarily unavailable"))
        .doAnswer(invocation->{memory.actions.add("source.progress_saved");return null;})
        .when(memory.store).commit(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq("source.progress_saved"),
            org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(com.oadig.syncsrv.platform.persistence.Store.Change[].class));
    assertThrows(java.io.IOException.class,()->p.finish(s.id,"PAUSED"));
    p.finish(s.id,false);assertEquals("PAUSED",p.list().get(0).run().status);
    assertEquals(1,memory.actions.stream().filter("source.progress_saved"::equals).count());
  }

}
