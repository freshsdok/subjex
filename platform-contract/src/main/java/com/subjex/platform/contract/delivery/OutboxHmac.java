package com.subjex.platform.contract.delivery;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * OutboxHmac — 出箱 HMAC：用共享密钥证明发送方，替代帧里的操作员口令。
 * <p>
 * Signature is HMAC-SHA256 over a canonical string. Timestamp skew is limited so a captured frame ages out.
 * 签名是对规范串的 HMAC-SHA256。时间戳有偏差上限，截获的帧会过期。
 */
public final class OutboxHmac {

    public static final int MIN_SECRET_LENGTH = 32;
    public static final Duration MAX_SKEW = Duration.ofMinutes(5);
    public static final String SCHEME = "hmac-sha256";

    private OutboxHmac() {
    }

    public static void requireSecret(String secret) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                    "outbox HMAC secret must be at least " + MIN_SECRET_LENGTH + " characters (OUTBOX_HMAC_SECRET)");
        }
    }

    public static String sign(
            String secret,
            String eventName,
            String traceparent,
            boolean failureRequested,
            long authTimestampMillis,
            String eventBody) {
        requireSecret(secret);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(canonical(
                    eventName, traceparent, failureRequested, authTimestampMillis, eventBody)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    public static boolean verify(
            String secret,
            String eventName,
            String traceparent,
            boolean failureRequested,
            long authTimestampMillis,
            String eventBody,
            String signature,
            Clock clock) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        long now = clock.instant().toEpochMilli();
        long skew = Math.abs(now - authTimestampMillis);
        if (skew > MAX_SKEW.toMillis()) {
            return false;
        }
        String expected = sign(secret, eventName, traceparent, failureRequested, authTimestampMillis, eventBody);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }


    /**
     * Verify with current secret, then optional previous (rotation overlap) —
     * 先用当前密钥校验，再试可选旧密钥（轮换重叠期）。
     */
    public static boolean verifyAny(
            String secret,
            String previousSecret,
            String eventName,
            String traceparent,
            boolean failureRequested,
            long authTimestampMillis,
            String eventBody,
            String signature,
            Clock clock) {
        if (verify(secret, eventName, traceparent, failureRequested, authTimestampMillis, eventBody, signature, clock)) {
            return true;
        }
        if (previousSecret == null || previousSecret.isBlank() || previousSecret.equals(secret)) {
            return false;
        }
        try {
            requireSecret(previousSecret);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return verify(
                previousSecret,
                eventName,
                traceparent,
                failureRequested,
                authTimestampMillis,
                eventBody,
                signature,
                clock);
    }

    static String canonical(
            String eventName,
            String traceparent,
            boolean failureRequested,
            long authTimestampMillis,
            String eventBody) {
        return String.join(
                "\n",
                eventName,
                traceparent,
                failureRequested ? "true" : "false",
                Long.toString(authTimestampMillis),
                eventBody == null ? "" : eventBody);
    }
}
