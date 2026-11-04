package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.oadig.syncsrv.modules.discovery.*;
import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.modules.config.*;
import com.oadig.syncsrv.modules.restore.RestoreDestination;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DeviceExecutionTest {
 @TempDir Path root;
 MemoryStore memory; DeviceExecution execution; final String agent="a".repeat(32);
 @BeforeEach void init() throws Exception {
  memory=new MemoryStore();execution=new DeviceExecution(memory.store);
  when(memory.store.encrypt(anyString())).thenAnswer(i->i.getArgument(0));
  when(memory.store.decrypt(anyString())).thenAnswer(i->i.getArgument(0));
  AgentService.Device d=new AgentService.Device();d.id=agent;d.status="APPROVED";memory.put("agent",agent,d);
 }
 Endpoint endpoint(String name)throws Exception{Endpoint e=new Endpoint();e.id=Ids.next();e.name=name;e.root=Files.createDirectory(root.resolve(name)).toString();e.agentId=agent;e.agentRoot=e.root;memory.put("endpoint",e.id,e);return e;}
 TaskContext task(){Run r=new Run();r.id=Ids.next();r.actor="test";return new TaskContext(r,new BandwidthLimiter(0),0);}
 Storage noContent(Endpoint e)throws Exception{return new EndpointStorage(e,new TransferTest.Delegate(new LocalStorage(Path.of(e.root))){public InputStream read(String p,long o)throws Exception{throw new AssertionError("center must not read file content");}public InputStream read(String p,long o,long n)throws Exception{throw new AssertionError("center must not read file content");}public void write(String p,long o,InputStream i,long n)throws Exception{throw new AssertionError("center must not relay file content");}});}
 AutoCloseable worker(AtomicInteger copies)throws Exception{
  execution.poll(agent);AtomicBoolean stop=new AtomicBoolean();Thread t=new Thread(()->{while(!stop.get())try{
   var c=execution.poll(agent);if(c==null){Thread.sleep(5);continue;}var w=execution.work(c);
   if(!execution.report(agent,new DeviceExecution.Report(c.id,"RUNNING","HASHING",0,0,"",0,""))){execution.report(agent,new DeviceExecution.Report(c.id,"CANCELLED","HASHING",0,0,"",0,"cancelled"));continue;}
   Path from=Path.of(w.source().endpoint().root).resolve(w.from());byte[] content=Files.readAllBytes(from);
   if(w.kind().equals("COPY")){Path to=Path.of(w.target().endpoint().root).resolve(w.to());Files.createDirectories(to.getParent());Files.write(to,content);copies.incrementAndGet();}
   execution.report(agent,new DeviceExecution.Report(c.id,"COMPLETED",w.kind().equals("COPY")?"VERIFYING":"HASHING",w.kind().equals("COPY")?content.length:0,content.length,Ids.hash(content),content.length,""));
  }catch(InterruptedException ex){return;}catch(Exception ex){throw new RuntimeException(ex);}});t.start();return ()->{stop.set(true);t.interrupt();t.join(2000);};
 }
 @Test void sameDeviceCopiesAndHashesWithoutCenterFileReadsAndRestoresIntoSubdirectory()throws Exception{
  Endpoint b=endpoint("B"),c=endpoint("C"),d=endpoint("D");byte[] bytes="old backup version".getBytes();Files.write(Path.of(b.root,"old"),bytes);
  AtomicInteger copies=new AtomicInteger();var transfer=new TransferService(memory.store);
  try(var worker=worker(copies);Storage source=noContent(b);Storage archive=noContent(c)){
   var task=task();transfer.copy(source,"old",archive,"old-version",bytes.length,Ids.hash(bytes),"version",task);
   assertEquals("AGENT_LOCAL",task.run.executionMode);assertEquals(bytes.length,task.run.bytes);
   assertEquals(Ids.hash(bytes),transfer.hash(archive,"old-version",bytes.length,task));
   var config=mock(ConfigurationService.class);when(config.endpoint(d.id)).thenReturn(d);
   try(var dest=new RestoreDestination(config,d.id,"point-in-time")){
    transfer.copy(archive,"old-version",dest,"file",bytes.length,Ids.hash(bytes),"restore",task);
   }
   assertArrayEquals(bytes,Files.readAllBytes(Path.of(d.root,"point-in-time/file")));assertEquals(2,copies.get());
  }
 }
 @Test void offlineFallsBackButStrictBindingAndUnresolvedWritesCannotRelay()throws Exception{
  Endpoint a=endpoint("A"),b=endpoint("B");var task=task();
  assertFalse(execution.copy(new Storage.Location(a,"in"),new Storage.Location(b,"out"),4,Ids.hash(new byte[4]),task));
  assertEquals("CENTER_RELAY",task.run.executionMode);b.allowRelay=false;
  assertThrows(IOException.class,()->execution.copy(new Storage.Location(a,"in"),new Storage.Location(b,"out"),4,Ids.hash(new byte[4]),task));
  b.allowRelay=true;DeviceExecution.Command c=new DeviceExecution.Command();c.id=Ids.next();c.status="RUNNING";c.agentId=agent;c.resource=ConfigurationService.location(b)+"|out";
  memory.put("device-pending",c.id,c);memory.put(DeviceExecution.KIND,c.id,c);
  assertThrows(IOException.class,()->execution.copy(new Storage.Location(a,"in"),new Storage.Location(b,"out"),4,Ids.hash(new byte[4]),task));
 }
 @Test void wrongAgentAndInvalidCompletionCannotConfirmArchive()throws Exception{
  Endpoint a=endpoint("A");var w=new DeviceExecution.Work("x","COPY",new DeviceExecution.BoundEndpoint(a,"",""),"in",new DeviceExecution.BoundEndpoint(a,"",""),"out",4,Ids.hash(new byte[4]),0);
  DeviceExecution.Command c=new DeviceExecution.Command();c.id="x";c.agentId=agent;c.kind="COPY";c.status="RUNNING";c.deadline=System.currentTimeMillis()+15000;c.payload=Json.write(w);memory.put(DeviceExecution.KIND,c.id,c);
  assertThrows(SecurityException.class,()->execution.report("wrong",new DeviceExecution.Report("x","COMPLETED","VERIFYING",4,4,w.hash(),4,"")));
  assertThrows(IllegalArgumentException.class,()->execution.report(agent,new DeviceExecution.Report("x","COMPLETED","VERIFYING",4,4,"0".repeat(64),4,"")));
  assertEquals("RUNNING",memory.store.get(DeviceExecution.KIND,c.id,DeviceExecution.Command.class).status);
 }
 @Test void bindingOverlapIsDetectedAcrossProtocols()throws Exception{
  Endpoint a=endpoint("a"),b=endpoint("b");a.agentRoot="/volume1/backup";b.agentRoot="/volume1/backup/sub";b.protocol="SMB";b.host="nas";b.root="share";
  assertTrue(ConfigurationService.overlap(a,b));
 }
 @Test void restoreOnSourceDeviceCannotOverwriteAnAgentOnlySource()throws Exception{
  Job job=new Job();job.id=Ids.next();job.sourceMode="AGENT";job.agentId=agent;job.agentRoot="/volume1/source";
  memory.put("job",job.id,job);
  Endpoint d=endpoint("restore");d.protocol="SMB";d.host="nas";d.root="share";d.agentRoot="/volume1/source";
  assertThrows(IllegalArgumentException.class,()->new ConfigurationService(memory.store).validateRestoreEndpoint(d,"restored"));
 }
 @Test void pauseRequiresTerminalReceiptBeforeAnotherWriter()throws Exception{
  Endpoint a=endpoint("A"),b=endpoint("B");execution.poll(agent);var task=task();
  ExecutorService pool=Executors.newSingleThreadExecutor();
  try {
   Future<Boolean> result=pool.submit(()->execution.copy(new Storage.Location(a,"in"),new Storage.Location(b,"out"),4,Ids.hash(new byte[4]),task));
   DeviceExecution.Command claimed=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
   while(claimed==null && System.nanoTime()<until){claimed=execution.poll(agent);Thread.sleep(10);}
   assertNotNull(claimed);task.cancelled.set(true);
   assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS));
   var saved=memory.store.get(DeviceExecution.KIND,claimed.id,DeviceExecution.Command.class);
   assertEquals("CANCEL_REQUESTED",saved.status);
   assertFalse(execution.report(agent,new DeviceExecution.Report(saved.id,"RUNNING","COPYING",0,0,"",0,"")));
   assertThrows(IOException.class,()->execution.copy(new Storage.Location(a,"in"),new Storage.Location(b,"out"),4,Ids.hash(new byte[4]),task()));
   execution.report(agent,new DeviceExecution.Report(saved.id,"CANCELLED","COPYING",0,0,"",0,"cancelled"));
   assertTrue(memory.store.list("device-pending",DeviceExecution.Command.class).isEmpty());
  } finally {pool.shutdownNow();}
 }
}
