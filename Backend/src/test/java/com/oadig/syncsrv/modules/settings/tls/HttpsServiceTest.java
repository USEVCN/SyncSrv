package com.oadig.syncsrv.modules.settings.tls;

import com.fasterxml.jackson.databind.ObjectMapper;



import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Instant;
import java.util.*;
import javax.net.ssl.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpsServiceTest {
    @TempDir Path root;
    static String certificate, key, ecCertificate, ecKey;
    static Path fixtures;

    @BeforeAll static void fixtures() throws Exception {
        fixtures = Files.createTempDirectory("syncsrv-https-fixtures-");
        String[] rsa = generate("rsa", "RSA"), ec = generate("ec", "EC");
        certificate = rsa[0]; key = rsa[1]; ecCertificate = ec[0]; ecKey = ec[1];
    }

    static String[] generate(String name, String algorithm) throws Exception {
        Path file = fixtures.resolve(name + ".p12");
        var args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", algorithm, "-validity", "2", "-dname", "CN=sync.example.com",
                "-ext", "SAN=dns:sync.example.com,dns:*.example.org,ip:127.0.0.1", "-ext", "BC=ca:false",
                "-ext", "EKU=serverAuth", "-storetype", "PKCS12", "-keystore", file.toString(),
                "-storepass", "test-only", "-keypass", "test-only", "-noprompt"));
        args.addAll(algorithm.equals("RSA") ? List.of("-keysize", "2048") : List.of("-groupname", "secp256r1"));
        var process = new ProcessBuilder(args).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor());
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(file)) { store.load(input, "test-only".toCharArray()); }
        return new String[]{pem("CERTIFICATE", store.getCertificate("server").getEncoded()),
                pem("PRIVATE KEY", store.getKey("server", "test-only".toCharArray()).getEncoded())};
    }
    static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
    }
    @AfterAll static void cleanup() throws Exception {
        try (var paths = Files.walk(fixtures)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    HttpsSettingsInput input(int port) { return new HttpsSettingsInput(true, "https://sync.example.com", port, "127.0.0.1", certificate, key); }
    HttpsService service() { var service = new HttpsService(root, 18010, false); service.attach((c, i) -> { }); return service; }

    @Test void verifiesRsaEcWildcardIpExpiryAndKeyPair() throws Exception {
        PemIdentity.parse(certificate, key, "https://sync.example.com");
        PemIdentity.parse(ecCertificate, ecKey, "https://sub.example.org");
        PemIdentity.parse(certificate, key, "https://127.0.0.1");
        assertThrows(IllegalArgumentException.class, () -> PemIdentity.parse(certificate, key, "https://nested.sub.example.org"));
        assertThrows(IllegalArgumentException.class, () -> PemIdentity.parse(certificate, key, "https://other.example"));
        assertThrows(IllegalArgumentException.class, () -> PemIdentity.parse(certificate, ecKey, "https://sync.example.com"));
        assertThrows(IllegalArgumentException.class, () -> PemIdentity.parse(certificate, key, "https://sync.example.com", Date.from(Instant.now().plusSeconds(3 * 86400))));
        assertFalse(input(18014).toString().contains("BEGIN"));
    }

    @Test void boundsAndSanitizesCertificateInput() {
        for (String url : List.of("http://sync.example.com", "https://user:secret@sync.example.com", "https://sync.example.com/path",
                "https://sync.example.com?q=secret", "https://sync.example.com#fragment", "https://sync.example.com:70000"))
            assertThrows(IllegalArgumentException.class, () -> PemIdentity.origin(url));
        assertThrows(IllegalArgumentException.class, () -> PemIdentity.parse("x".repeat(131073), key, "https://sync.example.com"));
        assertThrows(IllegalArgumentException.class, () -> PemIdentity.parse(certificate, "-----BEGIN ENCRYPTED PRIVATE KEY-----", "https://sync.example.com"));
    }

    @Test void validatesWithoutWritingAndNeverReturnsPrivateMaterial() throws Exception {
        var service = service();
        var metadata = service.check(input(18014));
        assertTrue(metadata.containsKey("sha256"));
        assertFalse(Files.exists(root.resolve("active.properties")));
        assertFalse(metadata.toString().contains("PRIVATE KEY"));
        assertFalse(service.status().toString().contains(key));
    }

    @Test void savesRestoresAndKeepsFilesRestricted() throws Exception {
        var service = service();
        assertEquals(true, service.save(input(18014), "admin").get("active"));
        assertFalse(new ObjectMapper().writeValueAsString(service.status()).contains("BEGIN"));
        var restored = new HttpsService(root, 18010, false);
        assertEquals(false, restored.status().get("active"));
        restored.attach((c, i) -> { });
        assertEquals(true, restored.status().get("active"));
        assertEquals(18014, restored.status().get("port"));
        if (Files.getFileAttributeView(root, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(Files::isRegularFile).toList())
                    assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
            }
        }
        restored.save(new HttpsSettingsInput(false, "https://sync.example.com", 18014, "127.0.0.1", null, null), "admin");
        assertEquals(false, restored.status().get("active"));
        assertEquals(true, restored.status().get("certificateInstalled"));
    }

    @Test void rejectsHalfIdentityConflictAndUnavailableTransport() {
        var service = service();
        assertThrows(IllegalArgumentException.class, () -> service.save(input(18010), "admin"));
        assertThrows(IllegalArgumentException.class, () -> service.save(new HttpsSettingsInput(true, "https://sync.example.com", 18014, "127.0.0.1", certificate, null), "admin"));
        assertThrows(IllegalArgumentException.class, () -> new HttpsService(root, 18010, false).save(input(18014), "admin"));
        assertFalse(Files.exists(root.resolve("active.properties")));
    }

    @Test void rejectedListenerReplacementKeepsPreviousBytesAndIdentity() throws Exception {
        var service = new HttpsService(root, 18010, false);
        service.attach((c, i) -> { if (c.port() == 18015) throw new IOException("occupied"); });
        service.save(input(18014), "admin");
        byte[] before = Files.readAllBytes(root.resolve("active.properties"));
        assertThrows(IllegalArgumentException.class, () -> service.save(new HttpsSettingsInput(true, "https://sync.example.com", 18015, "127.0.0.1", ecCertificate, ecKey), "admin"));
        assertArrayEquals(before, Files.readAllBytes(root.resolve("active.properties")));
        assertEquals(18014, service.status().get("port"));
        assertEquals(true, service.status().get("active"));
        assertEquals(PemIdentity.parse(certificate, key, "https://sync.example.com").metadata().get("sha256"),
                ((Map<?, ?>) service.status().get("certificate")).get("sha256"));
        try (var paths = Files.list(root)) { assertEquals(1, paths.filter(Files::isDirectory).count()); }
    }

    @Test void damagedSettingsFilePreventsAnyListenerReplacement() throws Exception {
        var service = service();
        service.save(input(18014), "admin");
        var ports = new ArrayList<Integer>();
        service.attach((c, i) -> ports.add(c.port()));
        Files.delete(root.resolve("active.properties"));
        Files.createDirectory(root.resolve("active.properties"));
        Files.writeString(root.resolve("active.properties/keep"), "do not replace");
        assertThrows(IllegalArgumentException.class, () -> service.save(input(18015), "admin"));
        assertEquals(List.of(18014), ports);
        assertEquals(true, service.status().get("active"));
    }

    @Test void damagedStartupDoesNotPreventOriginalEndpointRecovery() throws Exception {
        var service = service();
        service.save(input(18014), "admin");
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(p -> p.getFileName().toString().equals("private-key.pem")).toList()) Files.writeString(path, "bad");
        }
        var restored = new HttpsService(root, 18010, false);
        restored.attach((c, i) -> { if (c.enabled() && i == null) throw new IOException("missing"); });
        assertEquals(false, restored.status().get("active"));
        assertFalse(restored.status().get("error").toString().isBlank());
    }

    @Test void disabledDefaultsNeedNoCertificateOrDomain() {
        var service = service();
        assertEquals(false, service.save(new HttpsSettingsInput(false, "", 18014, "127.0.0.1", null, null), "admin").get("active"));
    }

    @Test void actualHttpTlsRenewalBindChangePortConflictAndDisable() throws Exception {
        var tomcat = new org.apache.catalina.startup.Tomcat();
        tomcat.setBaseDir(root.resolve("tomcat").toString()); tomcat.setPort(0);
        var http = tomcat.getConnector();
        var context = tomcat.addContext("", root.toString());
        org.apache.catalina.startup.Tomcat.addServlet(context, "check", new jakarta.servlet.http.HttpServlet() {
            @Override protected void doGet(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) throws IOException {
                res.getWriter().write(req.isSecure() ? "HTTPS OK" : "HTTP OK");
            }
        });
        context.addServletMappingDecoded("/*", "check"); tomcat.start();
        try {
            int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            var transport = new TomcatHttpsTransport(tomcat.getService());
            var config = new HttpsService.Config(true, "https://sync.example.com", port, "127.0.0.1", "");
            var rsa = PemIdentity.parse(certificate, key, config.publicUrl());
            var ec = PemIdentity.parse(ecCertificate, ecKey, config.publicUrl());
            transport.apply(config, rsa); assertTrue(get(port, rsa).contains("HTTPS OK"));
            transport.apply(config, ec); assertTrue(get(port, ec).contains("HTTPS OK"));
            transport.apply(new HttpsService.Config(true, config.publicUrl(), port, "0.0.0.0", ""), ec);
            assertTrue(get(port, ec).contains("HTTPS OK"));
            try (var occupied = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
                var bad = new HttpsService.Config(true, config.publicUrl(), occupied.getLocalPort(), "127.0.0.1", "");
                assertThrows(Exception.class, () -> transport.apply(bad, rsa));
                assertTrue(get(port, ec).contains("HTTPS OK"));
            }
            try (var input = new URL("http://127.0.0.1:" + http.getLocalPort() + "/").openStream()) {
                assertEquals("HTTP OK", new String(input.readAllBytes()));
            }
            transport.apply(new HttpsService.Config(false, config.publicUrl(), port, "0.0.0.0", ""), null);
            assertThrows(Exception.class, () -> get(port, ec));
        } finally { tomcat.stop(); tomcat.destroy(); }
    }


    @Test void auditFailureRestoresDiskAndListenerWithoutLoggingSecrets() throws Exception {
        var events = new ArrayList<String>();
        var fail = new java.util.concurrent.atomic.AtomicBoolean(false);
        var service = new HttpsService(root, 18010, false, (actor, action, detail) -> {
            events.add(actor + ":" + action + ":" + detail);
            if (action.equals("https.changed") && fail.get()) throw new IOException("audit unavailable");
        });
        service.attach((c,i)->{});
        service.save(input(18014), "admin");
        byte[] before = Files.readAllBytes(root.resolve("active.properties"));
        fail.set(true);
        assertThrows(IllegalArgumentException.class, () -> service.save(input(18015), "admin"));
        assertArrayEquals(before, Files.readAllBytes(root.resolve("active.properties")));
        assertEquals(18014, service.status().get("port"));
        assertEquals(true, service.status().get("active"));
        assertTrue(events.stream().anyMatch(v -> v.contains("https.change_failed")));
        assertTrue(events.stream().noneMatch(v -> v.contains("BEGIN") || v.contains(key)));
    }

    @Test void failedAuditIntentMakesNoListenerOrFileChanges() {
        var service = new HttpsService(root, 18010, false, (actor,action,detail)->{throw new IOException("audit unavailable");});
        var changed = new java.util.concurrent.atomic.AtomicBoolean(false);
        service.attach((c,i)->changed.set(true));
        assertThrows(IllegalArgumentException.class, () -> service.save(input(18014), "admin"));
        assertFalse(changed.get());
        assertFalse(Files.exists(root.resolve("active.properties")));
    }

    @Test void onlyAdminCanReadCheckOrSaveAndMalformedInputIsRedacted() throws Exception {
        var mocked = mock(HttpsService.class);
        var controller = new HttpsController(mocked);
        var request = new MockHttpServletRequest();
        request.setAttribute("role", "OPERATOR");
        assertThrows(SecurityException.class, () -> controller.get(request));
        assertThrows(SecurityException.class, () -> controller.validate(request));
        assertThrows(SecurityException.class, () -> controller.save(request));
        verifyNoInteractions(mocked);
        request.setAttribute("role", "ADMIN");
        request.setContent("secret malformed json".getBytes());
        assertFalse(assertThrows(IllegalArgumentException.class, () -> controller.validate(request)).getMessage().contains("secret"));
        var oversized = new MockHttpServletRequest(); oversized.setAttribute("role","ADMIN"); oversized.setContent(new byte[196609]);
        assertEquals(413, assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.validate(oversized)).getStatusCode().value());
    }

    private static String get(int port, PemIdentity identity) throws Exception {
        var store = KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null, null);
        store.setCertificateEntry("leaf", identity.chain[0]);
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        var context = SSLContext.getInstance("TLS"); context.init(null, trust.getTrustManagers(), null);
        try (var plain = new Socket("127.0.0.1", port);
             var socket = (SSLSocket) context.getSocketFactory().createSocket(plain, "sync.example.com", port, true)) {
            socket.setSoTimeout(5000);
            var parameters = socket.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS"); socket.setSSLParameters(parameters);
            socket.startHandshake();
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: sync.example.com\r\nConnection: close\r\n\r\n".getBytes());
            String response = new String(socket.getInputStream().readAllBytes());
            assertTrue(response.contains("200")); return response;
        }
    }
}
