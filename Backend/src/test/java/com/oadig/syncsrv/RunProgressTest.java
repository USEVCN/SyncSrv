package com.oadig.syncsrv;

import com.oadig.syncsrv.modules.transfer.RunProgress;
import com.oadig.syncsrv.shared.Models.Run;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunProgressTest {
  @Test void countsLogicalFilesSeparatelyFromArchiveAndVerificationAndFreezesOnStop() {
    AtomicLong clock=new AtomicLong();RunProgress p=new RunProgress(clock::get);
    Run run=new Run();run.status="RUNNING";p.start();
    p.plan("folder","MKDIR",0);p.plan("folder/file","WRITE",1000);p.plan("empty","WRITE",0);p.planned();
    p.begin("folder","MKDIR",0);p.finishFile("COMPLETED","");
    p.begin("folder/file","WRITE",1000);p.beginTransfer(200,"TRANSFERRING");p.transferred(300);
    run.verifiedBytes=1500;clock.set(4_000_000_000L);
    var s=p.snapshot(run);
    assertEquals(500,s.processedBytes());assertEquals(1000,s.totalBytes());
    assertEquals(0,s.processedFiles());assertEquals(2,s.totalFiles());
    assertEquals(75,s.transferBytesPerSecond());assertEquals(375,s.verificationBytesPerSecond());
    assertNull(s.estimatedTotalMillis(), "wait for a complete file, including its verification");
    p.beginTransfer(0,"ARCHIVING");p.transferred(3000);
    assertEquals(500,p.snapshot(run).processedBytes());
    p.finishFile("COMPLETED","");p.begin("empty","WRITE",0);p.finishFile("COMPLETED","");
    assertEquals(2,p.snapshot(run).processedFiles());
    run.status="COMPLETED";p.finish(run);clock.set(10_000_000_000L);
    assertEquals(4000,p.snapshot(run).elapsedMillis());assertEquals(0,p.snapshot(run).transferBytesPerSecond());
  }
  @Test void estimateCountsTheInitialScanOnceAndUsesCompletedFileWork() {
    AtomicLong clock=new AtomicLong();RunProgress p=new RunProgress(clock::get);
    Run run=new Run();run.status="RUNNING";p.start();
    clock.set(120_000_000_000L); // Two minutes of initial target verification.
    p.plan("a","DELETE",1000);p.plan("b","DELETE",1000);p.planned();
    p.begin("a","DELETE",1000);p.beginTransfer(0,"ARCHIVING");p.transferred(1000);
    clock.set(125_000_000_000L);
    assertNull(p.snapshot(run).estimatedTotalMillis()); // Verification is still running.
    clock.set(130_000_000_000L);p.finishFile("COMPLETED","");
    assertEquals(140000,p.snapshot(run).estimatedTotalMillis());
    p.begin("b","DELETE",1000);clock.set(135_000_000_000L);
    assertEquals(140000,p.snapshot(run).estimatedTotalMillis());
    clock.set(145_000_000_000L);
    assertNull(p.snapshot(run).estimatedTotalMillis(), "an overdue sample cannot promise zero time left");
  }
  @Test void idleSamplingDecaysAndPausedPartialRemainsButDoesNotCountAsACompletedFile() {
    AtomicLong clock=new AtomicLong();RunProgress p=new RunProgress(clock::get);
    Run run=new Run();run.status="RUNNING";p.start();p.plan("file","WRITE",100);p.planned();
    p.begin("file","WRITE",100);p.beginTransfer(0,"TRANSFERRING");p.transferred(30);
    for(int second=1;second<=7;second++){clock.set(second*1_000_000_000L);p.snapshot(run);}
    assertEquals(0,p.snapshot(run).transferBytesPerSecond());
    run.status="PAUSED";p.finish(run);var s=p.snapshot(run);
    assertEquals(30,s.processedBytes());assertEquals(0,s.processedFiles());assertNull(s.estimatedTotalMillis());
    assertEquals("PAUSED",p.page(0,run).entries().get(0).status());
  }
  @Test void largePlansArePagedAndAcknowledgingAnOldPageCannotDropNewProgress() {
    RunProgress p=new RunProgress();Run run=new Run();run.status="RUNNING";p.start();
    for(int i=0;i<205;i++)p.plan("f"+i,"WRITE",1);
    assertFalse(p.snapshot(run).planComplete());assertNull(p.snapshot(run).estimatedTotalMillis());
    p.planned();var old=p.pending(run);assertEquals(3,old.size());
    p.begin("f0","WRITE",1);p.finishFile("COMPLETED","");p.saved(old);
    assertEquals(1,p.pending(run).size());assertEquals(100,p.page(1,run).entries().size());
    assertEquals(5,p.page(2,run).entries().size());assertTrue(p.page(3,run).entries().isEmpty());
    p.begin("f1","WRITE",1);p.finishFile("SKIPPED","superseded");
    assertEquals(204,p.snapshot(run).totalFiles());assertEquals(204,p.snapshot(run).totalBytes());
  }
}
