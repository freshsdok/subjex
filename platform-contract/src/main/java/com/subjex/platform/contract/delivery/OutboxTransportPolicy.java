package com.subjex.platform.contract.delivery;

import java.util.Arrays;

/**
 * OutboxTransportPolicy — 出箱传输策略：非开发环境未配 TLS / HMAC 时失败关闭。
 * <p>
 * HMAC secret is always required (≥ {@link OutboxHmac#MIN_SECRET_LENGTH}). TLS may be omitted only when
 * the {@code local} Spring profile is active, or when {@code allowInsecure} is explicitly true (tests).
 * HMAC 密钥始终必填。仅 {@code local} profile 或显式 {@code allowInsecure}（测试）可省略 TLS。
 */
public final class OutboxTransportPolicy {

    private OutboxTransportPolicy() {
    }

    /**
     * Fail-closed: non-local without TLS/HMAC refuses startup -
     * 失败关闭：非 local 未配 TLS/HMAC 则拒绝启动。
     */
    public static void requireReady(String hmacSecret, boolean tlsEnabled, boolean allowInsecure, String[] activeProfiles) {
        OutboxHmac.requireSecret(hmacSecret);
        if (tlsEnabled) {
            return;
        }
        if (allowInsecure || hasLocalProfile(activeProfiles)) {
            return;
        }
        throw new IllegalStateException(
                "outbox TLS is required outside the local profile "
                        + "(set platform.delivery.tls.enabled=true with keystore/truststore, "
                        + "or use SPRING_PROFILES_ACTIVE=local / platform.delivery.allow-insecure=true for laptop/tests only)");
    }

    private static boolean hasLocalProfile(String[] activeProfiles) {
        if (activeProfiles == null) {
            return false;
        }
        return Arrays.stream(activeProfiles).anyMatch("local"::equals);
    }
}
