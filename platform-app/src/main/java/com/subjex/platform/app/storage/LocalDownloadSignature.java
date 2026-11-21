package com.subjex.platform.app.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * LocalDownloadSignature — HMAC-SHA256 over tenant, object key, and expiry (WP3-2 / ADR 0015 D3).
 * 对租户、对象键与过期时刻做 HMAC-SHA256，供本地盘签发/核验签名 URL。
 * <p>
 * The link binds object identity and expiry. It is not an unauthenticated public share,
 * and it does not re-run the authorizer on redeem (same as an S3 presigned GET).
 * 链接绑定对象身份与过期时间。不是未认证公开分享；兑换时不再跑授权判定（与 S3 预签名 GET 一致）。
 */
public final class LocalDownloadSignature {

    private static final String HMAC = "HmacSHA256";

    private final byte[] keyBytes;

    public LocalDownloadSignature(String signingKey) {
        this.keyBytes = signingKey == null || signingKey.isBlank()
                ? new byte[0]
                : signingKey.getBytes(StandardCharsets.UTF_8);
    }

    public boolean configured() {
        return keyBytes.length > 0;
    }

    /**
     * Hex HMAC of the canonical payload — 规范载荷的十六进制 HMAC。
     */
    public String sign(String tenantId, String objectKey, Instant expiresAt) {
        requireConfigured();
        requireParts(tenantId, objectKey, expiresAt);
        return hmacHex(canonical(tenantId, objectKey, expiresAt));
    }

    /**
     * Constant-time signature check (does not itself enforce expiry).
     * 恒定时间校验签名（过期由调用方按时钟判定）。
     */
    public boolean matches(String tenantId, String objectKey, Instant expiresAt, String signature) {
        if (!configured()
                || tenantId == null
                || tenantId.isBlank()
                || objectKey == null
                || objectKey.isBlank()
                || expiresAt == null
                || signature == null
                || signature.isBlank()) {
            return false;
        }
        String expected = hmacHex(canonical(tenantId, objectKey, expiresAt));
        String provided = signature.trim().toLowerCase(Locale.ROOT);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Build a redeem URL under {@link ObjectDownloadEndpoint#REDEEM_PATH}.
     * 构造兑换 URL。
     */
    public String redeemUrl(String publicBaseUrl, String tenantId, String objectKey, Instant expiresAt) {
        String sig = sign(tenantId, objectKey, expiresAt);
        String base = publicBaseUrl == null ? "" : publicBaseUrl;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base
                + ObjectDownloadEndpoint.REDEEM_PATH
                + "?tenantId="
                + enc(tenantId)
                + "&objectKey="
                + enc(objectKey)
                + "&expires="
                + expiresAt.getEpochSecond()
                + "&sig="
                + sig;
    }

    static String canonical(String tenantId, String objectKey, Instant expiresAt) {
        return tenantId + "\n" + objectKey + "\n" + expiresAt.getEpochSecond();
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(keyBytes, HMAC));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new IllegalStateException(
                    "platform.storage.download.signing-key is required for local-disk signed URLs. "
                            + "本地盘签名下载必须配置 HMAC 密钥。");
        }
    }

    private static void requireParts(String tenantId, String objectKey, Instant expiresAt) {
        if (tenantId == null || tenantId.isBlank() || objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("tenant and object key are required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
