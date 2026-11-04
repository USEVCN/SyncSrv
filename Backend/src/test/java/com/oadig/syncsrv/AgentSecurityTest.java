package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.Job;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentSecurityTest {
  String sign(PrivateKey key, String message) throws Exception {
    Signature s = Signature.getInstance("Ed25519");
    s.initSign(key);
    s.update(message.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(s.sign());
  }

  @Test
  void enrollmentProofApprovalSignaturesReplayAndRevocation() throws Exception {
    MemoryStore memory = new MemoryStore();
    ConfigurationService config = mock(ConfigurationService.class);
    AgentService agents = new AgentService(memory.store, config);
    KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
    var invite = agents.invitation("admin");
    String proof = sign(keys.getPrivate(), invite.token() + "\n" + publicKey + "\nsource");
    var enrollment =
        new AgentService.Enrollment(
            invite.token(), "source", "linux", "amd64", "test", publicKey, proof);
    var device = agents.enroll(enrollment);
    assertEquals(device.id, agents.enroll(enrollment).id);
    assertEquals("PENDING", device.status);
    Job j = new Job();
    j.id = "job";
    j.agentId = device.id;
    j.discoveryMode = "EVENTS";
    when(config.job("job")).thenReturn(j);
    var events =
        new ChangeInbox.Batch(
            "epoch", List.of(new ChangeInbox.Event("1", "job", "file", "CHANGE", Ids.now())));
    assertThrows(SecurityException.class, () -> agents.receive(device.id, events));
    agents.authorize(device.id, true, "admin");
    String timestamp = Long.toString(Instant.now().getEpochSecond()),
        nonce = Ids.next(),
        path = "/agent/v1/events";
    byte[] body = Json.bytes(events);
    String signed =
        sign(
            keys.getPrivate(),
            "POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + Ids.hash(body));
    assertEquals(
        device.id, agents.authenticate(device.id, "POST", path, timestamp, nonce, signed, body).id);
    assertThrows(
        SecurityException.class,
        () -> agents.authenticate(device.id, "POST", path, timestamp, nonce, signed, body));
    assertThrows(
        SecurityException.class,
        () -> agents.authenticate(device.id, "POST", path, timestamp, Ids.next(), signed, body));
    assertEquals(List.of("1"), agents.receive(device.id, events));
    agents.authorize(device.id, false, "admin");
    assertThrows(SecurityException.class, () -> agents.receive(device.id, events));
  }

  @Test void revokedDeviceCanOnlySendAuthenticatedCleanupAndStorageReceipts() throws Exception {
    MemoryStore memory=new MemoryStore();var agents=new AgentService(memory.store,mock(ConfigurationService.class));
    KeyPair keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var d=new AgentService.Device();d.id=Ids.next();
    d.status="REVOKED";d.publicKey=Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());memory.put("agent",d.id,d);
    byte[] body="{}".getBytes(StandardCharsets.UTF_8);String timestamp=Long.toString(Instant.now().getEpochSecond());
    for(String path:List.of("/agent/v1/push/abort","/agent/v1/storage/report","/agent/v1/storage/poll","/agent/v1/push/direct-open")) {
      String nonce=Ids.next();String signature=sign(keys.getPrivate(),"POST\n"+path+"\n"+timestamp+"\n"+nonce+"\n"+Ids.hash(body));
      if(path.endsWith("/abort") || path.endsWith("/report")) {
        assertEquals(d.id,agents.authenticate(d.id,"POST",path,timestamp,nonce,signature,body).id);
        assertThrows(SecurityException.class,()->agents.authenticate(d.id,"POST",path,timestamp,nonce,signature,body));
      } else assertThrows(SecurityException.class,()->agents.authenticate(d.id,"POST",path,timestamp,nonce,signature,body));
    }
    assertThrows(SecurityException.class,()->agents.authenticate(d.id,"POST","/agent/v1/push/abort",timestamp,Ids.next(),"invalid",body));
    String nonce=Ids.next(),path="/agent/v1/config";
    String signature=sign(keys.getPrivate(),"GET\n"+path+"\n"+timestamp+"\n"+nonce+"\n"+Ids.hash(body));
    assertEquals(d.id,agents.authenticate(d.id,"GET",path,timestamp,nonce,signature,body).id);
    assertEquals("REVOKED",agents.configuration(d.id).status());assertTrue(agents.configuration(d.id).roots().isEmpty());
  }

}
