package com.subjex.platform.contract.delivery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * OutboxTls — 出箱 TLS：从 PKCS12 密钥库 / 信任库构造 {@link SSLContext}。
 * <p>
 * Consumer presents the server certificate. Publisher trusts that certificate. Mutual TLS (client cert)
 * is a later hardening step; HMAC remains the application-level credential on the frame.
 * 消费者出示服务端证书，发布方信任该证书。双向 TLS（客户端证书）是后续加固；帧上的应用层凭据仍是 HMAC。
 */
public final class OutboxTls {

    private OutboxTls() {
    }

    public static SSLContext serverContext(Path keystorePath, char[] keystorePassword) {
        try {
            KeyStore keyStore = load(keystorePath, keystorePassword);
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(keyStore, keystorePassword);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return context;
        } catch (GeneralSecurityException | IOException ex) {
            throw new IllegalStateException("outbox TLS keystore could not be loaded", ex);
        }
    }

    public static SSLContext clientContext(Path truststorePath, char[] truststorePassword) {
        try {
            KeyStore trustStore = load(truststorePath, truststorePassword);
            TrustManagerFactory trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trusts.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trusts.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException ex) {
            throw new IllegalStateException("outbox TLS truststore could not be loaded", ex);
        }
    }

    private static KeyStore load(Path path, char[] password) throws GeneralSecurityException, IOException {
        if (path == null) {
            throw new IllegalArgumentException("outbox TLS store path is missing");
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, password);
        }
        return store;
    }
}
