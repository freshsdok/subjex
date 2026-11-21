package com.subjex.platform.app.storage;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * ObjectDownloadProperties — TTL and local-HMAC settings for signed download URLs (WP3-2 / ADR 0015 D3).
 * 签名下载的 TTL 与本地 HMAC 配置。默认 PT15M；硬上限 PT1H，配置不得抬高该上限。
 * <p>
 * Keys / 配置键：
 * {@code platform.storage.download.ttl} ({@code PLATFORM_STORAGE_DOWNLOAD_TTL}),
 * {@code platform.storage.download.ttl-max} ({@code PLATFORM_STORAGE_DOWNLOAD_TTL_MAX}),
 * {@code platform.storage.download.public-base-url} ({@code PLATFORM_STORAGE_DOWNLOAD_PUBLIC_BASE_URL}),
 * {@code platform.storage.download.signing-key} ({@code PLATFORM_STORAGE_DOWNLOAD_SIGNING_KEY}).
 */
public final class ObjectDownloadProperties {

    /** ADR default TTL — 默认有效期。 */
    public static final Duration DEFAULT_TTL = Duration.parse("PT15M");

    /** ADR hard cap; longer values are refused — 硬上限，超过则拒绝。 */
    public static final Duration HARD_CAP = Duration.parse("PT1H");

    private final Duration defaultTtl;
    private final Duration maxTtl;
    private final String publicBaseUrl;
    private final String signingKey;

    public ObjectDownloadProperties(
            Duration defaultTtl, Duration maxTtl, String publicBaseUrl, String signingKey) {
        Duration cap = maxTtl == null ? HARD_CAP : maxTtl;
        if (cap.isZero() || cap.isNegative()) {
            throw new IllegalArgumentException("platform.storage.download.ttl-max must be positive");
        }
        // Config cannot raise the ADR hard cap — 配置不得把硬上限抬到 PT1H 以上。
        if (cap.compareTo(HARD_CAP) > 0) {
            cap = HARD_CAP;
        }
        Duration def = defaultTtl == null ? DEFAULT_TTL : defaultTtl;
        if (def.isZero() || def.isNegative()) {
            throw new IllegalArgumentException("platform.storage.download.ttl must be positive");
        }
        if (def.compareTo(cap) > 0) {
            def = cap;
        }
        this.defaultTtl = def;
        this.maxTtl = cap;
        String base = publicBaseUrl == null || publicBaseUrl.isBlank()
                ? "http://127.0.0.1:8080"
                : publicBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.publicBaseUrl = base;
        this.signingKey = signingKey == null ? "" : signingKey;
    }

    public Duration defaultTtl() {
        return defaultTtl;
    }

    public Duration maxTtl() {
        return maxTtl;
    }

    public String publicBaseUrl() {
        return publicBaseUrl;
    }

    public String signingKey() {
        return signingKey;
    }

    /**
     * Resolve a caller TTL: blank uses the default; above the effective max is refused.
     * 解析调用方 TTL：缺省用默认值；超过有效上限则拒绝（有效上限不超过 PT1H）。
     */
    public Duration resolveTtl(Duration requested) {
        if (requested == null) {
            return defaultTtl;
        }
        if (requested.isZero() || requested.isNegative()) {
            throw new IllegalArgumentException("download TTL must be positive");
        }
        if (requested.compareTo(maxTtl) > 0) {
            throw new IllegalArgumentException(
                    "download TTL exceeds " + maxTtl.toString().toUpperCase(Locale.ROOT)
                            + " (hard cap PT1H)");
        }
        return requested;
    }

    public static Duration parseIso(String value, Duration fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Duration.parse(value.trim());
    }

    public boolean hasSigningKey() {
        return !signingKey.isBlank();
    }

    @Override
    public String toString() {
        return "ObjectDownloadProperties{defaultTtl=" + defaultTtl
                + ", maxTtl=" + maxTtl
                + ", publicBaseUrl=" + publicBaseUrl
                + ", signingKey=" + (hasSigningKey() ? "(set)" : "(empty)")
                + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(defaultTtl, maxTtl, publicBaseUrl, signingKey);
    }
}
