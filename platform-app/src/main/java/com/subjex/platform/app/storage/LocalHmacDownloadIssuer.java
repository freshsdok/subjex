package com.subjex.platform.app.storage;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * LocalHmacDownloadIssuer — app-issued HMAC URL redeemed by the platform (WP3-2).
 * 本地盘后端：应用签发 HMAC URL，由平台兑换端点核验后回流出对象字节。
 */
public final class LocalHmacDownloadIssuer implements ObjectDownloadUrlIssuer {

    private final LocalDownloadSignature signature;
    private final String publicBaseUrl;
    private final Clock clock;

    public LocalHmacDownloadIssuer(
            LocalDownloadSignature signature, String publicBaseUrl, Clock clock) {
        this.signature = Objects.requireNonNull(signature, "signature");
        this.publicBaseUrl = publicBaseUrl == null ? "http://127.0.0.1:8080" : publicBaseUrl;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public IssuedDownloadLink issue(String tenantId, String objectKey, Duration ttl) {
        var expiresAt = clock.instant().plus(ttl);
        String url = signature.redeemUrl(publicBaseUrl, tenantId, objectKey, expiresAt);
        return new IssuedDownloadLink(url, expiresAt, ttl, IssuedDownloadLink.BACKEND_LOCAL);
    }
}
