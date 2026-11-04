package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.archive.*;
import com.oadig.syncsrv.modules.archive.ArchiveModels.*;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveFaultTest {
  @TempDir Path root;
  ArchiveCleanupTest.Fixture fixture() throws Exception {
    var tests = new ArchiveCleanupTest(); tests.root = root;
    var f = tests.new Fixture(); f.job.archiveThreads = 1; return f;
  }

  @Test void absentFileWithoutIntentNeverRemovesItsVersionRegistration() throws Exception {
    var f=fixture(); var plan=f.preview(); var item=f.version(plan);
    Files.delete(f.file(item));
    Exception error=assertThrows(Exception.class,()->f.archive.execute(f.job.id,plan.id,f.cleanup(plan)));
    assertTrue(error.getMessage().contains("删除意图之前已缺失"));
    assertNotNull(f.memory.store.get("version",item.versionIds().get(0),Version.class));
    assertEquals("FAILED",f.archive.get(f.job.id,plan.id).state);
  }

  @Test void permissionFailureAfterIntentCannotBeMistakenForConfirmedAbsence() throws Exception {
    var f=fixture(); var plan=f.preview(); var item=f.version(plan);
    var first=new ArchiveService(f.memory.store,f.config,e->new TransferTest.Delegate(new LocalStorage(Path.of(e.root))){
      public void delete(String path,boolean directory) throws Exception {
        if(path.equals(item.location()))throw new IOException("disconnect before delete");super.delete(path,directory);
      }
    });
    assertThrows(Exception.class,()->first.execute(f.job.id,plan.id,f.cleanup(plan)));
    var denied=new ArchiveService(f.memory.store,f.config,e->new TransferTest.Delegate(new LocalStorage(Path.of(e.root))){
      public Storage.Item stat(String path) throws Exception {
        if(path.equals(item.location()))throw new AccessDeniedException(path);return super.stat(path);
      }
    });
    assertThrows(Exception.class,()->denied.execute(f.job.id,plan.id,f.cleanup(plan)));
    assertTrue(Files.exists(f.file(item)));
    assertNotNull(f.memory.store.get("version",item.versionIds().get(0),Version.class));
    assertFalse(f.memory.actions.contains("archive.file_absent_after_intent"));
    f.archive.execute(f.job.id,plan.id,f.cleanup(plan));
    assertEquals("COMPLETED",f.archive.get(f.job.id,plan.id).state);
  }

  @Test void fileBecomingDirectoryIsNotRecursivelyDeleted() throws Exception {
    var f=fixture(); var plan=f.preview(); var item=f.version(plan);
    Files.delete(f.file(item));Files.createDirectory(f.file(item));
    Files.writeString(f.file(item).resolve("unrelated"),"keep");
    assertThrows(Exception.class,()->f.archive.execute(f.job.id,plan.id,f.cleanup(plan)));
    assertEquals("keep",Files.readString(f.file(item).resolve("unrelated")));
    assertNotNull(f.memory.store.get("version",item.versionIds().get(0),Version.class));
  }

  @Test void contentChangedAfterHashBeforeDeleteRemainsUntouched() throws Exception {
    var f=fixture(); var plan=f.preview(); var item=f.version(plan);
    long changed=Files.getLastModifiedTime(f.file(item)).toMillis()+60_000;
    var service=new ArchiveService(f.memory.store,f.config,e->new TransferTest.Delegate(new LocalStorage(Path.of(e.root))){
      public InputStream read(String path,long offset,long length) throws Exception {
        InputStream input=super.read(path,offset,length);
        return new FilterInputStream(input){
          public void close() throws IOException {
            super.close();if(path.equals(item.location())){
              Files.writeString(f.file(item),"NEW");Files.setLastModifiedTime(f.file(item),FileTime.fromMillis(changed));
            }
          }
        };
      }
    });
    assertThrows(Exception.class,()->service.execute(f.job.id,plan.id,f.cleanup(plan)));
    assertEquals("NEW",Files.readString(f.file(item)));
    assertNotNull(f.memory.store.get("version",item.versionIds().get(0),Version.class));
  }

  @Test void deleteAcknowledgementWithoutRemovalFailsAndCanResume() throws Exception {
    var f=fixture(); var plan=f.preview(); var item=f.version(plan);
    var service=new ArchiveService(f.memory.store,f.config,e->new TransferTest.Delegate(new LocalStorage(Path.of(e.root))){
      public void delete(String path,boolean directory) throws Exception {
        if(!path.equals(item.location()))super.delete(path,directory);
      }
    });
    assertThrows(Exception.class,()->service.execute(f.job.id,plan.id,f.cleanup(plan)));
    assertTrue(Files.exists(f.file(item)));
    assertNotNull(f.memory.store.get("version",item.versionIds().get(0),Version.class));
    f.archive.execute(f.job.id,plan.id,f.cleanup(plan));
    assertEquals("COMPLETED",f.archive.get(f.job.id,plan.id).state);
  }

  @Test void cancellationDuringHashLeavesFileAndReusesSamePlan() throws Exception {
    var f=fixture(); var plan=f.preview(); var item=f.version(plan);var task=f.cleanup(plan);
    var service=new ArchiveService(f.memory.store,f.config,e->new TransferTest.Delegate(new LocalStorage(Path.of(e.root))){
      public InputStream read(String path,long offset,long length) throws Exception {
        if(path.equals(item.location()))task.cancelled.set(true);return super.read(path,offset,length);
      }
    });
    assertThrows(Exception.class,()->service.execute(f.job.id,plan.id,task));
    assertEquals("PAUSED",f.archive.get(f.job.id,plan.id).state);
    assertTrue(Files.exists(f.file(item)));
    assertThrows(Exception.class,()->f.preview(),"retired plans must be resumed, not replaced");
    f.archive.execute(f.job.id,plan.id,f.cleanup(plan));
    var completed=f.archive.get(f.job.id,plan.id);
    assertEquals("COMPLETED",completed.state);assertEquals(plan.estimatedBytes,completed.deletedBytes);
  }

  @Test void expiredConfirmationAndWrongJobCannotRetireSnapshots() throws Exception {
    var f=fixture();var plan=f.preview();var before=ArchiveCleanupTest.contents(f.c);
    assertThrows(Exception.class,()->f.archive.confirm("other-job",plan.id,true,"test"));
    plan.createdAt=Instant.now().minus(Duration.ofHours(2)).toString();f.memory.put("archive-plan",plan.id,plan);
    assertThrows(Exception.class,()->f.archive.confirm(f.job.id,plan.id,true,"test"));
    assertThrows(Exception.class,()->f.archive.execute(f.job.id,plan.id,f.cleanup(plan)));
    assertEquals(before,ArchiveCleanupTest.contents(f.c));assertNotNull(f.sync.snapshot(f.oldest.id()));
  }

  @Test void retryScopeCannotBeExpandedAfterRetirement() throws Exception {
    var f=fixture();var plan=f.preview();var item=f.version(plan);
    var failed=new ArchiveService(f.memory.store,f.config,e->{throw new IOException("offline");});
    assertThrows(Exception.class,()->failed.execute(f.job.id,plan.id,f.cleanup(plan)));
    var edited=f.archive.get(f.job.id,plan.id);edited.items=List.of(item);
    f.memory.put("archive-plan",plan.id,edited);var before=ArchiveCleanupTest.contents(f.c);
    assertThrows(Exception.class,()->f.archive.execute(f.job.id,plan.id,f.cleanup(plan)));
    assertEquals(before,ArchiveCleanupTest.contents(f.c));
  }

  @Test void queuedRunStatusIsAvailableWithoutGlobalStatePolling() throws Exception {
    var f=fixture();var plan=f.preview();var task=f.cleanup(plan);
    task.run.status="QUEUED";f.memory.put("run",task.run.id,task.run);
    assertEquals("QUEUED",f.archive.view(f.job.id,plan.id).runStatus);
    assertEquals("QUEUED",f.archive.plans(f.job.id).get(0).runStatus);
    task.run.status="INTERRUPTED";f.memory.put("run",task.run.id,task.run);
    assertEquals("PAUSED",f.archive.view(f.job.id,plan.id).state);
  }

  @Test void nestedDeletedAndEmptyFilesRemainReconstructableAfterCleanup() throws Exception {
    var f=fixture();
    Path directory=Files.createDirectories(f.a.resolve("目录 😀/nested"));
    Files.createDirectories(directory.resolve("empty-folder"));
    Files.write(directory.resolve("empty.txt"),new byte[0]);
    byte[] content=new byte[2*1024*1024+17];new Random(37).nextBytes(content);
    Files.write(directory.resolve("大文件.bin"),content);
    f.sync.sync(f.job,ArchiveCleanupTest.task());
    Snapshot point=f.retime(f.sync.snapshots().get(0),-2);
    try(var tree=Files.walk(f.a.resolve("目录 😀"))){for(Path path:tree.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    f.sync.sync(f.job,ArchiveCleanupTest.task());
    var plan=f.preview();assertTrue(plan.retained.stream().anyMatch(kept->kept.snapshot().id().equals(point.id())));
    var untouchedA=ArchiveCleanupTest.contents(f.a);var untouchedB=ArchiveCleanupTest.contents(f.b);
    f.archive.execute(f.job.id,plan.id,f.cleanup(plan));
    var restore=new com.oadig.syncsrv.modules.restore.RestoreService(f.memory.store,f.config,f.sync,f.transfer);
    restore.restore(point.id(),root.resolve("restored-nested").toString(),ArchiveCleanupTest.task());
    Path output=root.resolve("restored-nested/目录 😀/nested");
    assertArrayEquals(content,Files.readAllBytes(output.resolve("大文件.bin")));
    assertEquals(0,Files.size(output.resolve("empty.txt")));assertTrue(Files.isDirectory(output.resolve("empty-folder")));
    assertEquals(untouchedA,ArchiveCleanupTest.contents(f.a));assertEquals(untouchedB,ArchiveCleanupTest.contents(f.b));
  }
}
