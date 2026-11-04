package com.oadig.syncsrv.modules.operations;

import com.oadig.syncsrv.shared.Json;
import com.oadig.syncsrv.platform.persistence.Store;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class UpdateServerSourceTest {
  static final String FEED="https://old.example/api/v1/projects/syncsrv/channels/beta/manifest?os=linux&arch=x64";
  @TempDir Path root;
  Path policy, data, state;
  MockEnvironment env;
  List<String> events;
  @BeforeEach void setup() throws Exception {
    policy=root.resolve("deployment/update.json");Files.createDirectories(policy.getParent());
    Files.writeString(policy,"{\"manifest\":\""+FEED+"\",\"unrelated\":\"keep\"}");
    data=Files.createDirectory(root.resolve("data"));state=data.resolve("update-source.json");
    env=new MockEnvironment().withProperty("syncsrv.updates.config",policy.toString()).withProperty("syncsrv.data",data.toString());
    events=new ArrayList<>();
  }
  UpdateSourceConfiguration config(){return new UpdateSourceConfiguration(env,data,(actor,action,detail)->events.add(actor+":"+action+":"+Json.write(detail)));}
  CenterUpdateService service(UpdateSourceConfiguration source){return new CenterUpdateService(env,(u,m)->{throw new AssertionError("must not fetch while saving");},Json.MAPPER.createObjectNode(),"0.4.3",true,source);}
  Map<String,String> save(UpdateSourceConfiguration c,String url) throws Exception {return c.save(url,c.configuration().get("revision"),"test-admin");}

  @Test void storesInServiceStateAndPreservesReadOnlyDeploymentPolicyAndChannel() throws Exception {
    boolean posix=FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    if(posix)Files.setPosixFilePermissions(policy,PosixFilePermissions.fromString("r--------"));
    else Files.setAttribute(policy,"dos:readonly",true);
    byte[] before=Files.readAllBytes(policy);
    var service=service(config());var saved=service.saveServer("https://new.example:18761",service.sourceConfiguration().get("revision"),"test-admin");
    assertEquals("https://new.example:18761",saved.get("serverUrl"));assertEquals("beta",saved.get("channel"));
    assertArrayEquals(before,Files.readAllBytes(policy));
    if(posix){
      assertEquals(PosixFilePermissions.fromString("r--------"),Files.getPosixFilePermissions(policy));
      assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(state));
    }else{
      assertEquals(true,Files.getAttribute(policy,"dos:readonly"));
      Files.setAttribute(policy,"dos:readonly",false);
    }
    assertEquals("COMMITTED",Json.MAPPER.readTree(Files.readAllBytes(state)).path("status").asText());
    assertEquals(2,events.size());assertTrue(events.get(0).contains("source_change_requested"));assertTrue(events.get(1).contains("source_changed"));
    assertEquals(saved,new UpdateSourceConfiguration(env,data,(a,b,c)->{}).configuration());
  }
  @Test void rejectsCredentialsPathsQueriesAndHttpWithoutWritingOrAuditing() {
    for(String value:List.of("https://user:secret@example.com","https://example.com/api","https://example.com/?token=x","http://example.com"))
      assertThrows(IllegalArgumentException.class,()->save(config(),value));
    assertFalse(Files.exists(state));assertTrue(events.isEmpty());
  }
  @Test void rejectsStaleRevisionIncludingSameOriginAbaEdits() throws Exception {
    var c=config();String revision=c.configuration().get("revision");var a=save(c,"https://new.example");
    assertThrows(IllegalArgumentException.class,()->c.save("https://other.example",revision,"test-admin"));
    var b=save(c,"https://new.example");assertNotEquals(a.get("revision"),b.get("revision"));
    assertThrows(IllegalArgumentException.class,()->c.save("https://other.example",a.get("revision"),"test-admin"));
  }
  @Test void concurrentSavesCommitExactlyOneExpectedRevision() throws Exception {
    var c=config();String revision=c.configuration().get("revision");var pool=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);
    try {
      List<Future<Boolean>> jobs=new ArrayList<>();
      for(String origin:List.of("https://one.example","https://two.example"))jobs.add(pool.submit(()->{go.await();try{c.save(origin,revision,"test-admin");return true;}catch(IllegalArgumentException conflict){return false;}}));
      go.countDown();int successes=0;for(var job:jobs)if(job.get(3,TimeUnit.SECONDS))successes++;
      assertEquals(1,successes);assertEquals(2,events.size());assertNotEquals(revision,c.configuration().get("revision"));
    } finally {pool.shutdownNow();}
  }
  @Test void outboundCheckExcludesSourceMutation() throws Exception {
    var entered=new CountDownLatch(1);var done=new CountDownLatch(1);var c=config();String revision=c.configuration().get("revision");
    var service=new CenterUpdateService(env,(u,m)->{entered.countDown();assertTrue(done.await(3,TimeUnit.SECONDS));throw new IOException();},Json.MAPPER.createObjectNode(),"0.4.3",true,c);
    var pool=Executors.newSingleThreadExecutor();
    try {var check=pool.submit(service::check);assertTrue(entered.await(3,TimeUnit.SECONDS));assertThrows(IllegalStateException.class,()->service.saveServer("https://new.example",revision,"test-admin"));assertFalse(Files.exists(state));done.countDown();check.get(3,TimeUnit.SECONDS);}
    finally {done.countDown();pool.shutdownNow();}
  }
  @Test void auditIntentFailureLeavesNoStateOrSourceChange() throws Exception {
    var c=new UpdateSourceConfiguration(env,data,(a,b,d)->{throw new IOException("audit unavailable");});
    assertThrows(IOException.class,()->save(c,"https://new.example"));assertFalse(Files.exists(state));assertEquals("https://old.example",c.configuration().get("serverUrl"));
  }
  @Test void auditCompletionFailureRestoresExactPriorState() throws Exception {
    save(config(),"https://prior.example");byte[] before=Files.readAllBytes(state);
    var c=new UpdateSourceConfiguration(env,data,(a,b,d)->{events.add(b);if(b.equals("updates.source_changed"))throw new IOException("audit unavailable");});
    assertThrows(IOException.class,()->save(c,"https://new.example"));assertArrayEquals(before,Files.readAllBytes(state));assertEquals("https://prior.example",c.configuration().get("serverUrl"));assertTrue(events.contains("updates.source_change_failed"));
  }
  @Test void failedPendingOrCommitWriteRestoresPriorCommittedSource() throws Exception {
    for(int failAt:List.of(1,2)){
      save(config(),"https://prior.example");byte[] before=Files.readAllBytes(state);var count=new AtomicInteger();
      var c=new UpdateSourceConfiguration(env,data,(a,b,d)->{},(p,v)->{if(count.incrementAndGet()==failAt)throw new IOException("disk unavailable");UpdateSourceConfiguration.atomic(p,v);});
      assertThrows(IOException.class,()->save(c,"https://new.example"));assertArrayEquals(before,Files.readAllBytes(state));assertEquals("https://prior.example",c.configuration().get("serverUrl"));
    }
  }
  @Test void restartRestoresPendingIntentRatherThanUsingUncommittedSource() throws Exception {
    var c=config();save(c,"https://prior.example");var previous=Json.MAPPER.readTree(Files.readAllBytes(state));
    var pending=((com.fasterxml.jackson.databind.node.ObjectNode)previous).deepCopy().put("status","PENDING").put("serverUrl","https://uncommitted.example");pending.set("previous",previous);UpdateSourceConfiguration.atomic(state,pending);
    assertEquals("https://prior.example",config().configuration().get("serverUrl"));assertEquals(previous,Json.MAPPER.readTree(Files.readAllBytes(state)));
    assertTrue(events.stream().anyMatch(v->v.contains("source_recovered")));
  }
  @Test void failedRecoveryAuditKeepsPendingAndNeverFetches() throws Exception {
    var c=config();save(c,"https://prior.example");var pending=((com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.readTree(Files.readAllBytes(state))).put("status","PENDING");pending.putNull("previous");UpdateSourceConfiguration.atomic(state,pending);
    var blocked=new UpdateSourceConfiguration(env,data,(a,b,d)->{throw new IOException("audit unavailable");});
    assertEquals("CHECK_FAILED",service(blocked).check().status());assertEquals("PENDING",Json.MAPPER.readTree(Files.readAllBytes(state)).path("status").asText());
  }
  @Test void acceptsActualMacSystemAliasesButRejectsApplicationSymlinks() throws Exception {
    save(config(),"https://new.example");assertTrue(Files.exists(state));
    Path outside=Files.createDirectory(root.resolve("outside"));Files.delete(state);Files.createSymbolicLink(state,outside.resolve("target.json"));
    assertThrows(IOException.class,()->config().configuration());Files.delete(state);Files.delete(data);Files.createSymbolicLink(data,outside);
    assertThrows(IOException.class,()->config().configuration());assertFalse(Files.exists(outside.resolve("update-source.json")));
  }
  @Test void legacyHttpExceptionIsReadOnlyAndCannotBeWidenedByPreference() throws Exception {
    String origin="http://192.168.50.118:18760";
    Files.writeString(policy,Json.write(Map.of("manifest",FEED.replace("https://old.example",origin),"allowHttpOrigin",origin)));
    assertEquals(origin,config().configuration().get("serverUrl"));byte[] before=Files.readAllBytes(policy);save(config(),"https://new.example");assertArrayEquals(before,Files.readAllBytes(policy));
    var value=((com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.readTree(Files.readAllBytes(state))).put("serverUrl","http://192.168.50.119:18760");UpdateSourceConfiguration.atomic(state,value);
    assertThrows(IllegalArgumentException.class,()->config().configuration());
  }
  @Test void explicitStartupPolicyWinsAndRefusesSaving() throws Exception {
    save(config(),"https://saved.example");env.withProperty("syncsrv.updates.manifest",FEED);
    assertEquals("https://old.example",config().configuration().get("serverUrl"));assertThrows(IllegalArgumentException.class,()->save(config(),"https://new.example"));
  }
  @Test void changedDeploymentPolicyOrMalformedStateCannotFallBackToDefaultOrFetch() throws Exception {
    save(config(),"https://saved.example");Files.writeString(policy,Json.write(Map.of("manifest",FEED.replace("old.example","changed.example"))));
    assertEquals("CONTRACT_REJECTED",service(config()).check().status());
    Files.writeString(state,"[]");assertEquals("CHECK_FAILED",service(config()).check().status());
    Files.writeString(state," ".repeat(16385));assertEquals("CHECK_FAILED",service(config()).check().status());
  }
  @Test void appendsSignedAuditToAnIsolatedStoreWithTheRealActor() throws Exception {
    try(var store=new Store(root.resolve("isolated-test-db"))){
      var service=new CenterUpdateService(env,store);service.saveServer("https://new.example",service.sourceConfiguration().get("revision"),"test-admin");
      assertEquals(2,store.events(0,10).size());assertTrue(store.events(0,10).stream().allMatch(e->e.actor().equals("test-admin")&&e.action().startsWith("updates.source_")));
      assertEquals(2,store.verify().sequence());assertFalse(Files.exists(data.resolve("update-source.json")));
      Path saved=store.directory.resolve("update-source.json");
      var tampered=((com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.readTree(Files.readAllBytes(saved))).put("serverUrl","https://tampered.example");
      UpdateSourceConfiguration.atomic(saved,tampered);
      assertThrows(IllegalStateException.class,service::sourceConfiguration);
      assertEquals("CHECK_FAILED",service.check().status());
      Files.delete(saved);assertEquals("https://new.example",service.sourceConfiguration().get("serverUrl"));assertTrue(Files.exists(saved));assertEquals(4,store.verify().sequence());
    }
  }
  @Test void signedStoreRecoversEitherCrashWindowAndRejectsUnrelatedPendingState() throws Exception {
    for(boolean databaseCommitted:List.of(false,true))try(var store=new Store(root.resolve("crash-"+databaseCommitted))){
      var service=new CenterUpdateService(env,store);service.saveServer("https://prior.example",service.sourceConfiguration().get("revision"),"test-admin");
      Path file=store.directory.resolve("update-source.json");var previous=Json.MAPPER.readTree(Files.readAllBytes(file));
      com.fasterxml.jackson.databind.node.ObjectNode next;
      if(databaseCommitted){service.saveServer("https://next.example",service.sourceConfiguration().get("revision"),"test-admin");next=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.readTree(Files.readAllBytes(file));}
      else next=((com.fasterxml.jackson.databind.node.ObjectNode)previous).deepCopy().put("serverUrl","https://next.example").put("revision","b".repeat(32));
      var pending=next.deepCopy().put("status","PENDING");pending.set("previous",previous);UpdateSourceConfiguration.atomic(file,pending);
      assertEquals("https://prior.example",service.sourceConfiguration().get("serverUrl"));assertEquals(previous,Json.MAPPER.readTree(Files.readAllBytes(file)));
      assertEquals(databaseCommitted?6:4,store.verify().sequence());
      pending.set("previous",((com.fasterxml.jackson.databind.node.ObjectNode)previous).deepCopy().put("revision","c".repeat(32)));
      UpdateSourceConfiguration.atomic(file,pending);
      assertThrows(IllegalStateException.class,service::sourceConfiguration);
    }
  }

  @Test void readsLegacyUrlWithoutReplacingItAndKeepsItsChannel() throws Exception {
    Files.delete(policy);Path legacy=policy.resolveSibling("update.url");Files.writeString(legacy,FEED+"\n");
    var c=config();assertEquals("https://old.example",c.configuration().get("serverUrl"));
    assertEquals("beta",save(c,"https://new.example").get("channel"));assertEquals(FEED+"\n",Files.readString(legacy));assertFalse(Files.exists(policy));
  }

  @Test void metadataBackupRestoreRebuildsTheSameAuditedSourceWithoutDefaultFallback() throws Exception {
    Path original=root.resolve("backup-source");
    try(var store=new Store(original)){
      var service=new CenterUpdateService(env,store);service.saveServer("https://saved.example",service.sourceConfiguration().get("revision"),"test-admin");
    }
    Path backup=root.resolve("metadata-test-only.zip"),restored=root.resolve("restored-test-only");
    com.oadig.syncsrv.platform.persistence.MetadataTools.backup(original,backup);
    com.oadig.syncsrv.platform.persistence.MetadataTools.restore(backup,restored);
    assertFalse(Files.exists(restored.resolve("update-source.json")));
    try(var store=new Store(restored)){
      var service=new CenterUpdateService(env,store);
      assertTrue(Files.exists(restored.resolve("update-source.json")),"startup re-exports only the signed committed source");
      assertEquals("https://saved.example",service.sourceConfiguration().get("serverUrl"));
      assertTrue(Files.exists(restored.resolve("update-source.json")));assertEquals(4,store.verify().sequence());
      assertTrue(store.events(0,10).stream().anyMatch(e->e.action().equals("updates.source_export_recovered")));
    }
  }

}
