package com.oadig.syncsrv.modules.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Json;
import java.net.URI;
import java.net.http.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;

public final class AuditAnchor {
  private final Store store;
  private final URI uri;
  private final String receiverKey;
  private final HttpClient http;
  public AuditAnchor(Store store,String url,String receiverKey) {
    this(store,url,receiverKey,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
  }
  AuditAnchor(Store store,String url,String receiverKey,HttpClient http) {
    this.store=store;this.uri=URI.create(url);this.receiverKey=receiverKey;this.http=http;
    if (!"https".equals(uri.getScheme()) || uri.getUserInfo()!=null) throw new IllegalArgumentException("审计锚点必须使用 HTTPS");
  }
  public void checkpoint() throws Exception {
    // Check the independent monotonic head before advancing it: a restored older database cannot overwrite it.
    String nonce=UUID.randomUUID().toString();
    var fetched=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("X-SyncSrv-Nonce",nonce).GET().build(),HttpResponse.BodyHandlers.ofString());
    if(fetched.statusCode()!=200)throw new IllegalStateException("外部审计锚点不可读取");
    JsonNode previous=receipt(fetched.body(),nonce);
    long seq=previous.path("sequence").asLong(-1);
    var local=store.head();
    if(!previous.path("publicKey").asText().equals(store.publicKey())||seq<0||seq>local.sequence())throw new SecurityException("审计锚点检测到密钥变化或数据库回退");
    if(seq>0){var events=store.events(seq-1,1);if(events.size()!=1||!events.get(0).hash().equals(previous.path("head").asText()))throw new SecurityException("审计锚点与本地历史分叉");}
    if(previous.has("recovery")){
      var certificate=previous.path("recovery");String id=certificate.path("id").asText();
      if(!id.matches("[a-f0-9]{32}"))throw new SecurityException("无效的灾备授权收据");
      if(store.get("anchor-recovery",id,java.util.Map.class)==null)
        store.commit("system","audit.recovery_authorized",id,certificate,
            Store.Change.put("anchor-recovery",id,certificate));
    }
    local=store.head();
    var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
        .header("Content-Type","application/json").header("If-Match",Long.toString(seq)).header("X-SyncSrv-Nonce",nonce)
        .POST(HttpRequest.BodyPublishers.ofString(Json.write(store.header(local)))).build(),HttpResponse.BodyHandlers.ofString());
    if(response.statusCode()!=200&&response.statusCode()!=201)throw new IllegalStateException("审计锚点拒绝更新");
    JsonNode accepted=receipt(response.body(),nonce);
    if(accepted.path("sequence").asLong()!=local.sequence()||!accepted.path("head").asText().equals(local.head()))throw new SecurityException("外部审计收据未确认当前链头");
  }
  private JsonNode receipt(String text,String nonce) throws Exception {
    if(text.length()>16384)throw new SecurityException("锚点收据过大");
    JsonNode envelope=Json.MAPPER.readTree(text);
    byte[] raw=Base64.getDecoder().decode(envelope.path("payload").asText());
    Signature sig=Signature.getInstance("Ed25519");
    sig.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(receiverKey))));
    sig.update(raw);
    try{if(!sig.verify(Base64.getDecoder().decode(envelope.path("signature").asText())))throw new SecurityException("外部锚点收据签名无效");}
    catch(GeneralSecurityException invalid){throw new SecurityException("外部锚点收据签名无效",invalid);}
    JsonNode data=Json.MAPPER.readTree(raw);
    if(!nonce.equals(data.path("nonce").asText())||Math.abs(Instant.now().getEpochSecond()-data.path("time").asLong())>120)throw new SecurityException("外部锚点收据过期或被重放");
    return data;
  }
}
