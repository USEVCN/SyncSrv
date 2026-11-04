package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import com.oadig.syncsrv.modules.transfer.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ParallelWorkTest {
  @Test
  void failureStopsNewClaimsButDrainsOtherWritersBeforeReturning() throws Exception {
    var task=CenterParallelTest.task();
    var both=new CountDownLatch(2); var release=new CountDownLatch(1); var failed=new CountDownLatch(1);
    AtomicInteger called=new AtomicInteger(),closed=new AtomicInteger();
    ExecutorService executor=Executors.newSingleThreadExecutor();
    Future<?> run=executor.submit(()->{ParallelWork.run(List.of(0,1,2,3),2,task,()->new ParallelWork.Worker<Integer>() {
      public void apply(Integer n) throws Exception {
        called.incrementAndGet();both.countDown();assertTrue(both.await(5,TimeUnit.SECONDS));
        if(n==0)throw new IOException("archive unavailable");
        assertTrue(release.await(5,TimeUnit.SECONDS));
      }
      public void close(){closed.incrementAndGet();failed.countDown();}
    });return null;});
    try {
      assertTrue(failed.await(5,TimeUnit.SECONDS));
      assertFalse(run.isDone(),"failure may not return while another archive writer is active");
      release.countDown();
      var error=assertThrows(ExecutionException.class,()->run.get(5,TimeUnit.SECONDS));
      assertEquals("archive unavailable",error.getCause().getMessage());
      assertEquals(2,called.get());assertEquals(2,closed.get());
    } finally {release.countDown();executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS);}
  }

  @Test
  void pauseWaitsEvenForStorageThatDoesNotImmediatelyHonorInterrupts() throws Exception {
    var task=CenterParallelTest.task();
    var entered=new CountDownLatch(2);var interrupted=new CountDownLatch(2);var release=new CountDownLatch(1);
    AtomicInteger closed=new AtomicInteger();AtomicReference<Throwable> error=new AtomicReference<>();
    Thread coordinator=new Thread(()->{
      try {ParallelWork.run(List.of(1,2,3),2,task,()->new ParallelWork.Worker<Integer>() {
        public void apply(Integer value) throws Exception {
          entered.countDown();
          try {release.await();} catch(InterruptedException pause){interrupted.countDown();release.await();}
          task.check();
        }
        public void close(){closed.incrementAndGet();}
      });}catch(Throwable failure){error.set(failure);}
    });
    coordinator.start();
    try {
      assertTrue(entered.await(5,TimeUnit.SECONDS));task.cancelled.set(true);coordinator.interrupt();
      assertTrue(interrupted.await(5,TimeUnit.SECONDS));assertTrue(coordinator.isAlive());
      release.countDown();coordinator.join(5000);
      assertFalse(coordinator.isAlive());assertNotNull(error.get());assertEquals(2,closed.get());
    } finally {release.countDown();coordinator.interrupt();coordinator.join(5000);}
  }

  @Test
  void metricsAndVerificationScopesBelongToTheirOwnWorker() throws Exception {
    var task=CenterParallelTest.task();
    var both=new CountDownLatch(2);
    ParallelWork.run(List.of(1,2),2,task,()->new ParallelWork.Worker<Integer>() {
      public void apply(Integer n) throws Exception {
        task.stage("ARCHIVING","file"+n);
        try(var operation=task.metrics.operation("file"+n,"DELETE")) {
          task.metrics.read(n*100, n*100, true);
          long nested=task.metrics.nestedReadNanos();
          try(var verification=task.verifying("HASHING","file"+n,100)) {
            task.metrics.read(50, 50, false);
            both.countDown(); assertTrue(both.await(5,TimeUnit.SECONDS));
            assertEquals(nested+50,task.metrics.nestedReadNanos());
          }
          assertEquals("ARCHIVING",task.phase());
          task.metrics.write(100,n*100);
        }
      }
      public void close(){}
    });
    task.metrics.finish();
    var files=task.metrics.snapshot().slowestOperations();assertEquals(2,files.size());
    for(var file:files) {
      long expected=file.path().equals("file1") ? 100 : 200;
      assertEquals(expected,file.transferBytes());assertEquals(expected,file.writtenBytes());assertEquals(50,file.verificationBytes());
    }
  }
}
