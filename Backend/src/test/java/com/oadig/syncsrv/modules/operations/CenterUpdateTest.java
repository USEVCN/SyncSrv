package com.oadig.syncsrv.modules.operations;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CenterUpdateTest {
  @TempDir Path directory;
  MockEnvironment environment() { return new MockEnvironment().withProperty("syncsrv.updates.config", directory.resolve("missing.json").toString()).withProperty("syncsrv.data", directory.resolve("data").toString()); }
  @Test void currentAvailableAndIncompatibleResultsFetchOnlyTwoSmallDocuments() throws Exception {
    var f = new ReleaseContractTest.Fixture();
    // Match the fixture origin using server-owned configuration, never a request parameter.
    for (String current : List.of("0.4.3", "0.4.2", "0.3.0")) {
      var calls = new AtomicInteger(); var env = environment().withProperty("syncsrv.updates.manifest", ReleaseContractTest.FEED.toString());
      var service = new CenterUpdateService(env, (uri, maximum) -> { calls.incrementAndGet(); assertTrue(maximum <= 262144);
        return uri.getPath().endsWith("manifest") ? com.oadig.syncsrv.shared.Json.MAPPER.writeValueAsBytes(f.index) : f.sidecar;
      }, f.keys, current, false);
      var result = service.check(); assertEquals(current.equals("0.4.3") ? "UP_TO_DATE" : current.equals("0.4.2") ? "UPDATE_AVAILABLE" : "INCOMPATIBLE", result.status());
      assertFalse(result.platformSupported()); assertEquals(2, calls.get()); assertEquals("0.4.3", result.latestVersion());
    }
  }
  @Test void deniesNonAdminBeforeAnyExternalCheckAndHasNoInstallEndpoint() throws Exception {
    var service = mock(CenterUpdateService.class); var controller = new CenterUpdateController(service); var request = new MockHttpServletRequest();
    var input=new CenterUpdateController.SourceInput("https://repo.example","reviewed-revision");
    for(String role:List.of("", "AUDITOR", "OPERATOR")) {
      request.setAttribute("role",role);
      assertThrows(SecurityException.class,()->controller.check(request));
      assertThrows(SecurityException.class,()->controller.source(request));
      assertThrows(SecurityException.class,()->controller.saveSource(request));
    }
    verifyNoInteractions(service);
    request.setAttribute("role","ADMIN");request.setAttribute("actor","test-admin");request.setContent(com.oadig.syncsrv.shared.Json.MAPPER.writeValueAsBytes(input));
    controller.check(request);controller.source(request);controller.saveSource(request);
    verify(service).check();verify(service).sourceConfiguration();verify(service).saveServer(input.serverUrl(),input.expectedRevision(),"test-admin");
    var methods=Arrays.stream(CenterUpdateController.class.getDeclaredMethods()).toList();
    assertEquals(Set.of("/source","/check"),methods.stream().filter(m->m.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class))
        .flatMap(m->Arrays.stream(m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value())).collect(java.util.stream.Collectors.toSet()));
    assertEquals(1,methods.stream().filter(m->m.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class)).count());
    assertFalse(methods.stream().anyMatch(m->m.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)));
  }
  @Test void sourceWriteRejectsOversizedOrMalformedBodyWithoutLeakingDetails() throws Exception {
    var service=mock(CenterUpdateService.class);var controller=new CenterUpdateController(service);var request=new MockHttpServletRequest();request.setAttribute("role","ADMIN");
    request.setContent(new byte[4097]);
    assertEquals(413,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.saveSource(request)).getStatusCode().value());
    for(String raw:List.of("secret malformed input", "null")) {
      request.setContent(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertFalse(assertThrows(IllegalArgumentException.class,()->controller.saveSource(request)).getMessage().contains("secret"));
    }
    verifyNoInteractions(service);
  }
  @Test void rejectsBadConfigurationWithoutNetworkOrCredentialDisclosure() throws Exception {
    for (String feed : List.of("https://user:secret@repo.example/invalid", "http://repo.example/api/v1/projects/syncsrv/channels/stable/manifest?os=linux&arch=x64",
        ReleaseContractTest.FEED.toString().replace("syncsrv", "other"), ReleaseContractTest.FEED.toString().replace("arch=x64", "arch=arm64"))) {
      var f = new ReleaseContractTest.Fixture(); var calls = new AtomicInteger();
      var service = new CenterUpdateService(environment().withProperty("syncsrv.updates.manifest", feed), (u, m) -> { calls.incrementAndGet(); return new byte[0]; }, f.keys, "0.4.2", true);
      var result = service.check(); assertEquals("CONTRACT_REJECTED", result.status()); assertEquals(0, calls.get());
      assertFalse(result.toString().contains("secret"));
    }
  }
  @Test void signatureFailureStopsBeforeSidecarAndRetryDoesNotKeepOldSuccess() throws Exception {
    var f = new ReleaseContractTest.Fixture(); var calls = new AtomicInteger();
    var service = new CenterUpdateService(environment().withProperty("syncsrv.updates.manifest", ReleaseContractTest.FEED.toString()), (u, m) -> {
      calls.incrementAndGet(); return u.getPath().endsWith("manifest") ? com.oadig.syncsrv.shared.Json.MAPPER.writeValueAsBytes(f.index) : f.sidecar;
    }, f.keys, "0.4.2", true);
    assertEquals("UPDATE_AVAILABLE", service.check().status());
    f.index.put("signature", Base64.getEncoder().encodeToString(new byte[64]));
    var result = service.check(); assertEquals("SIGNATURE_REJECTED", result.status()); assertEquals("", result.latestVersion()); assertEquals(3, calls.get());
  }
  @Test void networkFailureIsClearAndDoesNotExposeInternalExceptions() throws Exception {
    var f = new ReleaseContractTest.Fixture();
    var service = new CenterUpdateService(environment(), (u, m) -> { throw new java.io.IOException("secret-detail"); }, f.keys, "0.4.2", true);
    var result = service.check(); assertEquals("CHECK_FAILED", result.status()); assertFalse(result.toString().contains("secret-detail"));
    for (Exception error : List.of(new javax.net.ssl.SSLHandshakeException("private detail"), new TimeoutException(), new CenterUpdateService.FetchFailure("更新源拒绝访问"))) {
      var next = new CenterUpdateService(environment(), (u, m) -> { throw error; }, f.keys, "0.4.2", true).check();
      assertEquals("CHECK_FAILED", next.status()); assertFalse(next.message().contains("private detail"));
      assertTrue(next.message().contains("TLS") || next.message().contains("超时") || next.message().contains("拒绝访问"));
    }
  }
  @Test void boundsBodySizeAndCancelsExcessData() throws Exception {
    var body = new CenterUpdateService.LimitedBody(2); var subscription = mock(Flow.Subscription.class); body.onSubscribe(subscription);
    body.onNext(List.of(ByteBuffer.wrap(new byte[3]))); verify(subscription).cancel();
    assertThrows(ExecutionException.class, () -> body.getBody().toCompletableFuture().get());
  }
  @Test void usesExistingInstallerConfigurationAndExplicitIntranetOrigin() throws Exception {
    var config = directory.resolve("update.json");
    Files.writeString(config, "{\"manifest\":\"http://192.168.50.118:18760/api/v1/projects/syncsrv/channels/stable/manifest?os=linux&arch=x64\",\"allowHttpOrigin\":\"http://192.168.50.118:18760\"}");
    var f = new ReleaseContractTest.Fixture(); var seen = new ArrayList<URI>();
    var service = new CenterUpdateService(new MockEnvironment().withProperty("syncsrv.updates.config", config.toString()).withProperty("syncsrv.data", directory.resolve("data").toString()), (u, m) -> { seen.add(u); throw new java.io.IOException(); }, f.keys, "0.4.2", true);
    assertEquals("CHECK_FAILED", service.check().status()); assertEquals("192.168.50.118", seen.get(0).getHost());
    Files.writeString(config, Files.readString(config).replace("\"http://192.168.50.118:18760\"}", "\"http://192.168.50.119:18760\"}"));
    assertEquals("CONTRACT_REJECTED", service.check().status()); assertEquals(1, seen.size());
  }
  @Test void preventsConcurrentOutboundChecks() throws Exception {
    var f = new ReleaseContractTest.Fixture(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var service = new CenterUpdateService(environment(), (u, m) -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); throw new java.io.IOException(); }, f.keys, "0.4.2", true);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var first = executor.submit(service::check); assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertEquals("CHECKING", service.check().status()); release.countDown(); assertEquals("CHECK_FAILED", first.get(3, TimeUnit.SECONDS).status());
    } finally { release.countDown(); executor.shutdownNow(); }
  }
  @Test void acceptsExplicitPrivateIpv6ButRejectsPublicOrUnscopedHttp() throws Exception {
    for (String host : List.of("[::1]", "[fd00::1]", "[2001:db8::1]", "8.8.8.8", "10.999.1.2", "0127.0.0.1")) {
      var f = new ReleaseContractTest.Fixture(); var calls = new AtomicInteger(); String origin = "http://" + host + ":18760";
      var env = environment().withProperty("syncsrv.updates.manifest", origin + ReleaseContractTest.FEED.getRawPath() + "?os=linux&arch=x64").withProperty("syncsrv.updates.allow-http-origin", origin);
      var result = new CenterUpdateService(env, (u, m) -> { calls.incrementAndGet(); throw new java.io.IOException(); }, f.keys, "0.4.2", true).check();
      assertEquals(host.startsWith("[::") || host.startsWith("[fd") ? "CHECK_FAILED" : "CONTRACT_REJECTED", result.status(), host);
      assertEquals(host.startsWith("[::") || host.startsWith("[fd") ? 1 : 0, calls.get());
    }
  }
}
