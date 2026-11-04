package com.oadig.syncsrv.modules.settings.tls;

import com.oadig.syncsrv.modules.settings.tls.HttpsSettingsInput;
import com.oadig.syncsrv.platform.persistence.Store;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Hot HTTPS listener, isolated from the existing main HTTP/legacy HTTPS configuration. */
@Service
public class HttpsService {
    record Config(boolean enabled, String publicUrl, int port, String bindAddress, String identity) { }
    interface Transport { void apply(Config config, PemIdentity identity) throws Exception; }

    interface Audit { void record(String actor, String action, Map<String,Object> detail) throws Exception; }
    private final Audit audit;
    private final Path directory;
    private final int mainPort;
    private final boolean legacyHttps;
    private Config saved = new Config(false, "", 18014, "127.0.0.1", "");
    private PemIdentity identity;
    private Transport transport;
    private boolean active;
    private String lastError = "";

    @Autowired
    public HttpsService(Environment environment, Store store) {
        this(store.directory.resolve("https"), environment.getProperty("server.port", Integer.class, 18010),
                environment.getProperty("server.ssl.enabled", Boolean.class, false),
                (actor, action, detail) -> store.commit(actor, action, "https", detail));
    }

    HttpsService(Path directory, int mainPort, boolean legacyHttps) {
        this(directory, mainPort, legacyHttps, (actor, action, detail) -> {});
    }

    HttpsService(Path directory, int mainPort, boolean legacyHttps, Audit audit) {
        this.audit = audit;
        this.directory = directory.toAbsolutePath().normalize();
        this.mainPort = mainPort;
        this.legacyHttps = legacyHttps;
        try { load(); }
        catch (Exception error) { lastError = "HTTPS 配置或证书不可用，请重新导入。原有入口仍可使用。"; }
    }

    private void load() throws Exception {
        HttpsFiles.checkParents(directory);
        Path path = directory.resolve("active.properties");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        var properties = new Properties();
        properties.load(new ByteArrayInputStream(HttpsFiles.read(path, 16384)));
        Config loaded = config(new HttpsSettingsInput(Boolean.parseBoolean(properties.getProperty("enabled")),
                properties.getProperty("publicUrl", ""), Integer.valueOf(properties.getProperty("port")),
                properties.getProperty("bindAddress"), null, null), properties.getProperty("identity", ""));
        validate(loaded);
        saved = loaded;
        if (!saved.identity().isEmpty()) {
            if (!saved.identity().matches("[0-9a-f-]{36}")) throw new IOException("证书记录无效");
            Path version = directory.resolve(saved.identity());
            identity = PemIdentity.parse(new String(HttpsFiles.read(version.resolve("fullchain.pem"), 131072), StandardCharsets.US_ASCII),
                    new String(HttpsFiles.read(version.resolve("private-key.pem"), 32768), StandardCharsets.US_ASCII), saved.publicUrl());
        }
    }

    @EventListener
    public synchronized void ready(ApplicationReadyEvent event) {
        if (event.getApplicationContext() instanceof ServletWebServerApplicationContext web
                && web.getWebServer() instanceof TomcatWebServer tomcat) {
            attach(new TomcatHttpsTransport(tomcat.getTomcat().getService()));
        }
    }

    synchronized void attach(Transport next) {
        transport = next;
        if (!saved.enabled()) return;
        try {
            if (identity == null) throw new IllegalStateException("缺少证书");
            transport.apply(saved, identity);
            active = true;
            lastError = "";
        } catch (Exception error) {
            active = false;
            lastError = "HTTPS 未启动，请核对证书、私钥和端口占用。原有入口仍可使用。";
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (transport == null) return;
        try { transport.apply(disabled(saved), null); } catch (Exception ignored) { }
        active = false;
    }

    public synchronized Map<String, Object> status() {
        var result = new LinkedHashMap<String, Object>();
        result.put("enabled", saved.enabled());
        result.put("active", active);
        result.put("publicUrl", saved.publicUrl());
        result.put("port", saved.port());
        result.put("bindAddress", saved.bindAddress());
        result.put("certificate", identity == null ? Map.of() : identity.metadata());
        result.put("certificateInstalled", identity != null);
        result.put("error", lastError);
        result.put("httpPort", mainPort);
        result.put("legacyHttps", legacyHttps);
        return result;
    }

    public synchronized Map<String, Object> check(HttpsSettingsInput input) {
        try {
            Config candidate = config(input, saved.identity());
            validate(candidate);
            return select(input, candidate).metadata();
        } catch (Exception error) { throw invalid(safeMessage(error)); }
    }

    public synchronized Map<String, Object> save(HttpsSettingsInput input, String actor) {
        final Config candidate;
        final PemIdentity next;
        if (input == null) throw invalid("请提供 HTTPS 配置");
        boolean replacing = present(input.certificatePem()) || present(input.privateKeyPem());
        try {
            candidate = config(input, saved.identity());
            validate(candidate);
            next = candidate.enabled() || replacing ? select(input, candidate) : identity;
            if (candidate.enabled() && transport == null) throw new IllegalArgumentException("HTTPS 服务尚未就绪，请稍后重试");
        } catch (Exception error) { throw invalid(safeMessage(error)); }

        Config previous = saved;
        PemIdentity prior = identity;
        boolean priorActive = active;
        Path created = null, settings = directory.resolve("active.properties");
        byte[] previousBytes;
        try {
            previousBytes = Files.exists(settings, LinkOption.NOFOLLOW_LINKS) ? HttpsFiles.read(settings, 16384) : null;
            audit.record(actor, "https.change_requested", detail(candidate, next));
        } catch (Exception error) { throw invalid("无法记录变更审计或读取原配置，HTTPS 未修改"); }
        boolean persisted = false;
        try {
            Config committed = candidate;
            if (replacing) {
                HttpsFiles.directory(directory);
                String version = UUID.randomUUID().toString();
                created = directory.resolve(version);
                HttpsFiles.directory(created);
                HttpsFiles.writeNew(created.resolve("fullchain.pem"), next.certificatePem);
                HttpsFiles.writeNew(created.resolve("private-key.pem"), next.privateKeyPem);
                committed = new Config(candidate.enabled(), candidate.publicUrl(), candidate.port(), candidate.bindAddress(), version);
            }
            if (transport != null) transport.apply(committed, next);
            HttpsFiles.atomicWrite(settings, properties(committed));
            persisted = true;
            audit.record(actor, "https.changed", detail(committed, next));
            saved = committed;
            identity = next;
            active = candidate.enabled();
            lastError = "";
            return status();
        } catch (Exception error) {
            boolean rollbackFailed = false;
            try {
                if (persisted) {
                    if (previousBytes == null) Files.deleteIfExists(settings);
                    else HttpsFiles.atomicWrite(settings, new String(previousBytes, StandardCharsets.ISO_8859_1));
                }
                if (transport != null) transport.apply(priorActive ? previous : disabled(previous), prior);
            } catch (Exception rollback) {
                rollbackFailed = true;
                active = false;
                lastError = "HTTPS 恢复失败，请通过原有入口检查配置。";
            }
            // If compensation failed, retain the referenced certificate for recovery.
            if (created != null && !rollbackFailed) {
                try {
                    Files.deleteIfExists(created.resolve("fullchain.pem"));
                    Files.deleteIfExists(created.resolve("private-key.pem"));
                    Files.deleteIfExists(created);
                } catch (IOException ignored) { }
            }
            try { audit.record(actor, "https.change_failed", Map.of("rollback", rollbackFailed ? "FAILED" : "RESTORED")); }
            catch (Exception ignored) { }
            throw invalid(rollbackFailed ? lastError : "HTTPS 未保存，已保留原配置。请核对审计、证书、目录权限和端口占用。");
        }
    }

    private static Map<String,Object> detail(Config value, PemIdentity id) {
        return Map.of("enabled", value.enabled(), "publicUrl", value.publicUrl(), "port", value.port(),
                "bindAddress", value.bindAddress(), "certificateSha256", id == null ? "" : id.metadata().get("sha256"));
    }

    private static String properties(Config value) throws IOException {
        var properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(value.enabled()));
        properties.setProperty("publicUrl", value.publicUrl());
        properties.setProperty("port", Integer.toString(value.port()));
        properties.setProperty("bindAddress", value.bindAddress());
        properties.setProperty("identity", value.identity());
        var output = new StringWriter();
        properties.store(output, "SyncSrv HTTPS listener");
        return output.toString();
    }

    private PemIdentity select(HttpsSettingsInput input, Config candidate) throws Exception {
        boolean certificate = present(input.certificatePem()), key = present(input.privateKeyPem());
        if (certificate != key) throw new IllegalArgumentException("换证时请同时选择证书链和私钥");
        if (certificate) return PemIdentity.parse(input.certificatePem(), input.privateKeyPem(), candidate.publicUrl());
        if (identity == null) throw new IllegalArgumentException("请先导入证书链和私钥");
        return PemIdentity.parse(identity.certificatePem, identity.privateKeyPem, candidate.publicUrl());
    }

    private Config config(HttpsSettingsInput input, String version) {
        if (input == null || input.enabled() == null) throw new IllegalArgumentException("请提供 HTTPS 开关状态");
        String url = input.publicUrl() == null ? "" : input.publicUrl().strip();
        if (!url.isEmpty() || Boolean.TRUE.equals(input.enabled())) url = PemIdentity.origin(url);
        return new Config(Boolean.TRUE.equals(input.enabled()), url,
                input.port() == null ? saved.port() : input.port(),
                input.bindAddress() == null ? saved.bindAddress() : input.bindAddress(), version);
    }

    private void validate(Config config) {
        if (config.port() < 1024 || config.port() > 65535 || config.port() == mainPort
                || !Set.of("0.0.0.0", "127.0.0.1").contains(config.bindAddress()))
            throw new IllegalArgumentException("HTTPS 端口需为 1024–65535，且不能与主服务端口相同");
    }

    private static Config disabled(Config value) { return new Config(false, value.publicUrl(), value.port(), value.bindAddress(), value.identity()); }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static String safeMessage(Exception error) { return error instanceof IllegalArgumentException ? error.getMessage() : "证书或私钥校验失败，请核对证书链与私钥是否匹配"; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
