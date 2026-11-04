package com.oadig.syncsrv.modules.operations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.net.http.*;import java.security.*;import java.time.*;import java.util.*;
import org.junit.jupiter.api.Test;
class AuditAnchorTest {
 @Test void signedChallengeResponseIsRequiredAndRollbackCannotAdvance()throws Exception{
  var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var store=mock(Store.class);var client=mock(HttpClient.class);
  String publicKey=Base64.getEncoder().encodeToString(key.getPublic().getEncoded());var head=new AuditHead(1,"local-head","center-key","LOCAL_SIGNED_CHAIN");
  when(store.head()).thenReturn(head);when(store.publicKey()).thenReturn("center-key");when(store.header(head)).thenReturn(new EvidenceHeader("fixture",head,"signature"));
  long[] previous={0};boolean[] corrupt={false};
  when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(i->{
   HttpRequest request=i.getArgument(0);boolean post=request.method().equals("POST");
   byte[] raw=Json.bytes(Map.of("sequence",post?1:previous[0],"head",post?"local-head":"", "publicKey","center-key","time",Instant.now().getEpochSecond(),"nonce",request.headers().firstValue("X-SyncSrv-Nonce").orElseThrow()));
   Signature signer=Signature.getInstance("Ed25519");signer.initSign(key.getPrivate());signer.update(raw);byte[] signature=signer.sign();if(corrupt[0])signature[0]^=1;
   HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(Json.write(Map.of("payload",Base64.getEncoder().encodeToString(raw),"signature",Base64.getEncoder().encodeToString(signature))));return response;
  });
  var anchor=new AuditAnchor(store,"https://independent.example/checkpoint",publicKey,client);anchor.checkpoint();verify(client,times(2)).send(any(),any());
  previous[0]=2;assertThrows(SecurityException.class,anchor::checkpoint);verify(client,times(3)).send(any(),any());
  previous[0]=0;corrupt[0]=true;assertThrows(SecurityException.class,anchor::checkpoint);verify(client,times(4)).send(any(),any());
 }
 @Test void insecureReceiverOriginIsRefused(){assertThrows(IllegalArgumentException.class,()->new AuditAnchor(mock(Store.class),"http://anchor/","key"));}
}
