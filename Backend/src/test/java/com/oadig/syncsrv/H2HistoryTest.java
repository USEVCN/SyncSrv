package com.oadig.syncsrv;
import static org.junit.jupiter.api.Assertions.*;
import static com.oadig.syncsrv.platform.persistence.Store.Change.*;
import com.oadig.syncsrv.platform.persistence.*;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.sql.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
class H2HistoryTest {
 @TempDir Path root;
 @Test void consistentHistorySnapshotDoesNotBlockWriter()throws Exception{
  try(Store store=new Store(root.resolve("db"))){
   store.commit("test","created","one",Map.of(),put("sample","one",Map.of("value",1)));
   var pool=Executors.newSingleThreadExecutor();
   try{store.readSnapshot(()->{
    assertEquals(1,store.get("sample","one",Map.class).get("value"));
    pool.submit(()->{try{store.commit("test","updated","one",Map.of(),put("sample","one",Map.of("value",2)));}catch(Exception e){throw new RuntimeException(e);}}).get(3,TimeUnit.SECONDS);
    assertEquals(1,store.get("sample","one",Map.class).get("value"));return null;
   });}finally{pool.shutdownNow();}
   assertEquals(2,store.get("sample","one",Map.class).get("value"));
  }
 }
 @Test void pagesNearestAndRunHeadsVerifySignedH2Documents()throws Exception{
  Path data=root.resolve("db");try(Store store=new Store(data)){
   for(int i=0;i<4;i++){Run run=new Run();run.id="run-"+i;run.jobId="job";run.kind="SYNC";run.status="COMPLETED";run.startedAt="2026-01-0"+(i+1)+"T00:00:00Z";store.commit("test","run","",Map.of(),put("run",run.id,run));}
   assertEquals(List.of("run-3","run-2"),store.history("run",2,0,Run.class).stream().map(v->v.id).toList());assertEquals("run-1",store.history("run",2,2,Run.class).get(0).id);assertEquals("run-3",store.runHeads().get(0).id);
   SnapshotSummary point=new SnapshotSummary("s","job",1,"2026-01-01T00:00:00Z","2026-01-01T01:00:00Z",1,1,6,"hash");
   store.commit("test","snapshot","s",Map.of(),put("snapshot-summary","s",point));assertEquals("s",store.nearestSnapshot("job","2026-01-01T02:00:00Z").id());
   try(Connection db=DriverManager.getConnection(MetadataTools.url(data),"sa","");Statement sql=db.createStatement()){sql.executeUpdate("UPDATE objects SET data='{}' WHERE kind='run' AND id='run-3'");}
   assertThrows(SecurityException.class,()->store.history("run",4,0,Run.class));
  }
 }
 @Test void nearestNeverSelectsFutureFractionalSecondAndHistoryUsesInstantOrder()throws Exception{
  try(Store store=new Store(root.resolve("precision"))){
   String[] dates={"2026-01-01T01:00:00Z","2026-01-01T01:00:00.000000001Z","2026-01-01T01:00:00.000000002Z"};
   for(int i=0;i<dates.length;i++){SnapshotSummary point=new SnapshotSummary("s"+i,"job",i+1,dates[i],dates[i],0,0,0,"hash");store.commit("test","snapshot","",Map.of(),put("snapshot-summary",point.id(),point));}
   assertEquals("s0",store.nearestSnapshot("job",dates[0]).id());assertEquals("s1",store.nearestSnapshot("job",dates[1]).id());
   assertEquals(List.of("s2","s1","s0"),store.history("snapshot-summary",3,0,SnapshotSummary.class).stream().map(SnapshotSummary::id).toList());
  }
 }

}
