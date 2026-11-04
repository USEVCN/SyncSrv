package com.oadig.syncsrv;
import static org.junit.jupiter.api.Assertions.*;
import com.oadig.syncsrv.modules.operations.*;
import com.oadig.syncsrv.modules.discovery.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
class RecoveryStateTest {
 @TempDir Path root;
 @Test void importPausesJobsAndInvalidatesCachedDeviceReceipts()throws Exception{
  var memory=new MemoryStore();Job job=new Job();job.id="job";job.sourceMode="AGENT";job.enabled=true;memory.put("job",job.id,job);
  var device=new AgentService.Device();device.id="agent";device.configVersion=5;memory.put("agent",device.id,device);
  var command=new DeviceExecution.Command();command.id="command";command.status="RUNNING";memory.put("device-pending",command.id,command);
  var session=new AgentPushService.Session();session.id="session";memory.put("push-session",session.id,session);
  RecoveryState.prepare(memory.store,"os:operator");assertFalse(memory.store.get("job",job.id,Job.class).enabled);assertEquals(6,memory.store.get("agent",device.id,AgentService.Device.class).configVersion);
  assertNull(memory.store.get("device-pending",command.id,DeviceExecution.Command.class));assertEquals("NOT_STARTED",memory.store.get(DeviceExecution.KIND,command.id,DeviceExecution.Command.class).status);
  assertEquals("ABANDONED",memory.store.get("push-session",session.id,AgentPushService.Session.class).status);
  assertNotNull(memory.store.get("recovery-state","pending",Map.class));
 }
 @Test void fullFileVerificationRetainsGuardOnCorruptContent()throws Exception{
  var memory=new MemoryStore();Path b=Files.createDirectory(root.resolve("B"));Files.writeString(b.resolve("file.txt"),"actual");
  Endpoint endpoint=new Endpoint();endpoint.id="B";endpoint.root=b.toString();memory.put("endpoint",endpoint.id,endpoint);
  Endpoint c=new Endpoint();c.id="C";c.root=Files.createDirectory(root.resolve("C")).toString();memory.put("endpoint",c.id,c);
  Job job=new Job();job.id="job";job.targetId="B";job.archiveId="C";memory.put("job",job.id,job);
  Node node=new Node("file.txt",false,6,0,Ids.hash("wrong!".getBytes()),"version");memory.put("current",job.id,new Node[]{node});
  memory.put("version","version",new Version("version",job.id,node.path(),"B",node.path(),6,node.hash(),Ids.now(),"INITIAL"));memory.put("recovery-state","pending",Map.of("required",true));
  assertThrows(Exception.class,()->StateFiles.verify(memory.store,"os:operator"));assertNotNull(memory.store.get("recovery-state","pending",Map.class));assertEquals("actual",Files.readString(b.resolve("file.txt")));
  memory.put("version","version",new Version("version",job.id,node.path(),"B",node.path(),6,Ids.hash("actual".getBytes()),Ids.now(),"INITIAL"));
  memory.put(com.oadig.syncsrv.modules.sync.CurrentIndex.kind(job.id),Ids.textHash(node.path()),new Node(node.path(),false,6,0,Ids.hash("actual".getBytes()),"version"));
  StateFiles.verify(memory.store,"os:operator");assertNull(memory.store.get("recovery-state","pending",Map.class));
 }
}
