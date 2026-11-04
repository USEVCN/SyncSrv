package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.archive.ArchiveModels.Plan;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.shared.Models.Endpoint;
import java.nio.file.*;
import java.util.*;
import org.apache.ftpserver.*;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.*;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Full retention/deletion/reconstruction over real loopback protocol servers, not mocked I/O. */
class ArchiveProtocolTest {
  @TempDir Path root;
  ArchiveCleanupTest.Fixture fixture() throws Exception {
    var tests=new ArchiveCleanupTest();tests.root=root;return tests.new Fixture();
  }
  Endpoint endpoint(String protocol,int port) {
    var e=new Endpoint();e.id="C";e.protocol=protocol;e.host="127.0.0.1";e.port=port;
    e.root="/";e.username="test";e.password="fixture-only";return e;
  }
  void contract(ArchiveCleanupTest.Fixture f,Endpoint e) throws Exception {
    when(f.config.endpoint("C")).thenReturn(e);f.memory.put("endpoint","C",e);
    var a=ArchiveCleanupTest.contents(f.a);var b=ArchiveCleanupTest.contents(f.b);
    Plan plan=f.preview();assertEquals(2,plan.items.size());
    f.archive.execute(f.job.id,plan.id,f.cleanup(plan));
    var done=f.archive.get(f.job.id,plan.id);
    assertEquals("COMPLETED",done.state);assertEquals(plan.estimatedBytes,done.deletedBytes);
    for(var item:plan.items)assertFalse(Files.exists(f.file(item)));
    assertEquals(a,ArchiveCleanupTest.contents(f.a));assertEquals(b,ArchiveCleanupTest.contents(f.b));
    assertEquals("leave me alone",Files.readString(f.c.resolve("unknown.txt")));
    var restore=new RestoreService(f.memory.store,f.config,f.sync,f.transfer);
    restore.restore(f.baseline.id(),root.resolve("restored").toString(),ArchiveCleanupTest.task());
    assertEquals("boundary version",Files.readString(root.resolve("restored/f.txt")));
    assertEquals("shared",Files.readString(root.resolve("restored/shared.txt")));
  }
  @Test void ftpArchiveCleanupRetainsReconstructableBaseline() throws Exception {
    var f=fixture();var factory=new FtpServerFactory();var listener=new ListenerFactory();
    listener.setPort(0);listener.setServerAddress("127.0.0.1");factory.addListener("default",listener.createListener());
    var user=new BaseUser();user.setName("test");user.setPassword("fixture-only");
    user.setHomeDirectory(f.c.toString());user.setAuthorities(List.of(new WritePermission()));factory.getUserManager().save(user);
    var server=factory.createServer();server.start();
    try{contract(f,endpoint("FTP",factory.getListener("default").getPort()));}finally{server.stop();}
  }
  @Test void sftpArchiveCleanupRetainsReconstructableBaseline() throws Exception {
    var f=fixture();var server=SshServer.setUpDefaultServer();server.setHost("127.0.0.1");server.setPort(0);
    var keys=new SimpleGeneratorHostKeyProvider(root.resolve("host.key"));keys.setAlgorithm("RSA");
    server.setKeyPairProvider(keys);server.setFileSystemFactory(new VirtualFileSystemFactory(f.c));
    server.setPasswordAuthenticator((u,p,s)->u.equals("test")&&p.equals("fixture-only"));
    server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));server.start();
    try{
      var e=endpoint("SFTP",server.getPort());e.hostKey=KeyUtils.getFingerPrint(keys.loadKeys(null).iterator().next().getPublic());
      contract(f,e);
    }finally{server.stop(true);}
  }
  @Test
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="syncsrv.smb.python",matches=".+")
  void smbArchiveCleanupRetainsReconstructableBaseline() throws Exception {
    var f=fixture();int port;
    try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
    var script=Path.of(getClass().getResource("/smb-fixture.py").toURI());
    var process=new ProcessBuilder(System.getProperty("syncsrv.smb.python"),script.toString(),"--root",f.c.toString(),"--port",""+port)
        .redirectErrorStream(true).redirectOutput(root.resolve("smb.log").toFile()).start();
    try{
      long deadline=System.nanoTime()+10_000_000_000L;
      while(true){try(var socket=new java.net.Socket("127.0.0.1",port)){break;}
        catch(java.io.IOException error){if(System.nanoTime()>deadline)throw error;Thread.sleep(50);}}
      var e=endpoint("SMB",port);e.root="test";contract(f,e);
    }finally{
      process.destroy();if(!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))process.destroyForcibly();
    }
  }
  @Test void webDavArchiveCleanupRetainsReconstructableBaseline() throws Exception {
    var f=fixture();var dav=new WebDavTest();dav.root=f.c;dav.start();
    try{dav.endpoint.id="C";contract(f,dav.endpoint);}finally{dav.stop();}
  }
}
