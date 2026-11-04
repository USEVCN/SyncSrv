package com.oadig.syncsrv.modules.settings.tls;

import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;

import javax.net.ssl.*;
import java.net.*;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;

/** Owns one connector only; the application listener and all its routing remain untouched. */
final class TomcatHttpsTransport implements HttpsService.Transport {
    private final Service service;
    private Connector connector;
    private HttpsService.Config config;
    private PemIdentity identity;
    private SSLHostConfig ssl;

    TomcatHttpsTransport(Service service) { this.service = service; }

    @Override public synchronized void apply(HttpsService.Config next, PemIdentity nextIdentity) throws Exception {
        if (!next.enabled()) {
            remove(connector);
            connector = null;
            config = null;
            identity = null;
            ssl = null;
            return;
        }
        if (nextIdentity == null) throw new IllegalArgumentException("缺少 HTTPS 证书");
        SSLHostConfig nextSsl = ssl(nextIdentity);
        if (connector != null && config.port() == next.port() && config.bindAddress().equals(next.bindAddress())) {
            var protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
            try {
                protocol.addSslHostConfig(nextSsl, true);
                probe(next, nextIdentity);
            } catch (Exception error) {
                protocol.addSslHostConfig(ssl, true);
                throw error;
            }
            config = next;
            identity = nextIdentity;
            ssl = nextSsl;
            return;
        }
        HttpsService.Config previous = config;
        PemIdentity prior = identity;
        // Changing the bind address on the same port requires releasing the old socket first.
        boolean samePort = connector != null && config.port() == next.port();
        if (samePort) {
            remove(connector);
            connector = null;
        }
        Connector candidate = null;
        try { candidate = start(next, nextIdentity, nextSsl); }
        catch (Exception error) {
            if (samePort) connector = start(previous, prior, ssl);
            throw error;
        }
        Connector old = connector;
        connector = candidate;
        config = next;
        identity = nextIdentity;
        ssl = nextSsl;
        remove(old);
    }

    private Connector start(HttpsService.Config next, PemIdentity id, SSLHostConfig nextSsl) throws Exception {
        Connector candidate = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        candidate.setPort(next.port());
        candidate.setScheme("https");
        candidate.setSecure(true);
        candidate.setThrowOnFailure(true);
        candidate.setProperty("address", next.bindAddress());
        candidate.setProperty("SSLEnabled", "true");
        candidate.setProperty("sslImplementationName", "org.apache.tomcat.util.net.jsse.JSSEImplementation");
        candidate.setProperty("maxThreads", "40");
        candidate.setProperty("maxConnections", "512");
        candidate.setProperty("connectionTimeout", "20000");
        candidate.setProperty("maxHttpRequestHeaderSize", "16384");
        ((AbstractHttp11Protocol<?>) candidate.getProtocolHandler()).addSslHostConfig(nextSsl);
        try {
            service.addConnector(candidate);
            if (!candidate.getState().isAvailable()) throw new IllegalStateException("HTTPS 端口未启动");
            probe(next, id);
            return candidate;
        } catch (Exception error) {
            remove(candidate);
            throw error;
        }
    }

    private void remove(Connector value) {
        if (value == null) return;
        service.removeConnector(value);
        try { value.stop(); } catch (Exception ignored) { }
        try { value.destroy(); } catch (Exception ignored) { }
    }

    private static SSLHostConfig ssl(PemIdentity identity) throws Exception {
        var config = new SSLHostConfig();
        config.setProtocols("TLSv1.2,TLSv1.3");
        var certificate = new SSLHostConfigCertificate(config, SSLHostConfigCertificate.Type.UNDEFINED);
        var store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        String password = UUID.randomUUID().toString();
        store.setKeyEntry("https", identity.key, password.toCharArray(), identity.chain);
        certificate.setCertificateKeystore(store);
        certificate.setCertificateKeyAlias("https");
        certificate.setCertificateKeystorePassword(password);
        certificate.setCertificateKeyPassword(password);
        config.addCertificate(certificate);
        return config;
    }

    private static void probe(HttpsService.Config next, PemIdentity identity) throws Exception {
        // Pin the exact leaf while still checking the hostname and actual TLS handshake.
        // This is a local check, not a public CA/DNS/port-forwarding assertion.
        var store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("leaf", identity.chain[0]);
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        var context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        String host = URI.create(next.publicUrl()).getHost();
        try (var plain = new Socket()) {
            plain.connect(new InetSocketAddress("127.0.0.1", next.port()), 3000);
            try (var tls = (SSLSocket) context.getSocketFactory().createSocket(plain, host, next.port(), true)) {
                tls.setSoTimeout(5000);
                var parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                if (!Arrays.equals(tls.getSession().getPeerCertificates()[0].getEncoded(), identity.chain[0].getEncoded()))
                    throw new SSLException("HTTPS 证书不一致");
            }
        }
    }
}
