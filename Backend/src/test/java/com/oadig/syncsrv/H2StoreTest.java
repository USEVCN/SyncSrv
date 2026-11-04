package com.oadig.syncsrv;
import static org.junit.jupiter.api.Assertions.*;
import static com.oadig.syncsrv.platform.persistence.Store.Change.*;
import com.oadig.syncsrv.platform.persistence.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class H2StoreTest {
  @TempDir Path root;
  Store open(Path data)throws Exception{return new Store(data);}
  @Test void initializeRollbackReopenBackupRestore()throws Exception{
    Path data=root.resolve("data");String secret;
    try(Store s=open(data)){
      secret=s.encrypt("secret中文");var changes=new ArrayList<Store.Change>();
      for(int i=0;i<501;i++)changes.add(put("sample","item-"+i,Map.of("path","目录/😀.txt","i",i)));
      s.commit("test","saved","fixture",Map.of(),changes.toArray(Store.Change[]::new));
      assertThrows(SQLException.class,()->s.commit("test","x".repeat(200),"fixture",Map.of(),put("sample","bad",Map.of())));
      assertNull(s.get("sample","bad",Map.class));assertEquals(1,s.verify().sequence());
      s.commit("test","update","fixture",Map.of(),put("sample","item-0",Map.of("i",999)));
      assertThrows(Exception.class,()->open(data));
    }
    Path zip=root.resolve("backup.zip"),restored=root.resolve("restored");
    MetadataTools.backup(data,zip);MetadataTools.restore(zip,restored);
    assertThrows(Exception.class,()->MetadataTools.restore(zip,restored));
    try(Store s=open(restored)){
      assertEquals(501,s.list("sample",Map.class).size());assertEquals(999,s.get("sample","item-0",Map.class).get("i"));
      assertEquals(2,s.verify().sequence());assertEquals("secret中文",s.decrypt(secret));
    }
  }
  @Test void lostCommitAcknowledgement()throws Exception{
    try(Store s=open(root.resolve("data"))){
      var f=Store.class.getDeclaredField("db");f.setAccessible(true);Connection actual=(Connection)f.get(s);
      f.set(s,java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
        try{Object result=m.invoke(actual,a);if(m.getName().equals("commit"))throw new SQLRecoverableException("lost ack");return result;}
        catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
      }));
      assertThrows(SQLException.class,()->s.commit("test","saved","fixture",Map.of(),put("sample","1",Map.of("i",1))));
      assertTrue(s.healthy());assertEquals(1,s.verify().sequence());assertNotNull(s.get("sample","1",Map.class));
    }
  }
  @Test void forceKilledProcess()throws Exception{
    Path data=root.resolve("crash"),marker=root.resolve("ready"),log=root.resolve("child.log");
    Process p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),CrashWriter.class.getName(),data.toString(),marker.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try{
      long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
      while(!Files.exists(marker)&&p.isAlive()&&System.nanoTime()<end)Thread.sleep(25);
      assertTrue(Files.exists(marker),()->{try{return Files.readString(log);}catch(Exception e){return e.toString();}});
      p.destroyForcibly();assertTrue(p.waitFor(10,TimeUnit.SECONDS));
      awaitWriterLockRelease(data.resolve("instance.lock"));
      try(Store s=open(data)){
        assertEquals(1,s.verify().sequence());assertNotNull(s.get("sample","committed",Map.class));assertNull(s.get("sample","pending",Map.class));
        s.commit("test","recovered","fixture",Map.of());assertEquals(2,s.verify().sequence());
      }
    }finally{p.destroyForcibly();}
  }
  @Test void missingDatabaseCannotSilentlyReplaceExistingInstallation() throws Exception {
    Path data=root.resolve("missing");
    try(Store s=open(data)){s.commit("test","saved","fixture",Map.of());}
    Files.delete(data.resolve("metadata.mv.db"));
    assertThrows(IllegalStateException.class,()->open(data));
  }
  @Test void restoredTamperingIsRejected() throws Exception {
    Path data=root.resolve("tamper");
    try(Store s=open(data)){s.commit("test","saved","fixture",Map.of(),put("sample","1",Map.of("i",1)));}
    try(Connection c=DriverManager.getConnection(MetadataTools.url(data),"sa","");Statement st=c.createStatement()){
      st.execute("UPDATE objects SET data='{\"i\":99}' WHERE id='1'");
    }
    assertThrows(Exception.class,()->MetadataTools.backup(data,root.resolve("invalid.zip")));
    assertFalse(Files.exists(root.resolve("invalid.zip")));
  }
  private void awaitWriterLockRelease(Path file) throws Exception {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
    do {
      try(var channel=java.nio.channels.FileChannel.open(file,StandardOpenOption.WRITE)) {
        var released=channel.tryLock();
        if(released!=null){released.release();return;}
      }
      Thread.sleep(25);
    }while(System.nanoTime()<deadline);
    fail("OS did not release the terminated writer's file lock");
  }
  public static class CrashWriter{
    public static void main(String[]args)throws Exception{
      Path data=Path.of(args[0]);Store s=new Store(data);
      s.commit("test","saved","fixture",Map.of(),put("sample","committed",Map.of("i",1)));
      Connection c=DriverManager.getConnection(MetadataTools.url(data),"sa","");c.setAutoCommit(false);
      try(Statement st=c.createStatement()){st.execute("INSERT INTO objects VALUES('sample','pending','{}')");}
      Files.writeString(Path.of(args[1]),"ready");Thread.sleep(60000);
    }
  }
}
