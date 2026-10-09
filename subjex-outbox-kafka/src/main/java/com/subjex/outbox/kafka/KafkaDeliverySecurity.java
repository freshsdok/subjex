package com.subjex.outbox.kafka;

import java.util.Arrays;

/**
 * KafkaDeliverySecurity — Kafka 出箱安全门：非 local 禁止明文（须 TLS 或 SASL）。
 * <p>
 * {@code SSL}, {@code SASL_SSL}, and {@code SASL_PLAINTEXT} count as secured. {@code PLAINTEXT} is allowed
 * only under the {@code local} profile or explicit {@code allowInsecure} (laptop/tests).
 * {@code SSL}/{@code SASL_SSL}/{@code SASL_PLAINTEXT} 视为已加固；明文仅 {@code local} 或显式 allowInsecure。
 */
public final class KafkaDeliverySecurity {

    private KafkaDeliverySecurity() {
    }

    public static void requireReady(String securityProtocol, boolean allowInsecure, String[] activeProfiles) {
        String protocol = securityProtocol == null || securityProtocol.isBlank()
                ? "PLAINTEXT"
                : securityProtocol.trim().toUpperCase();
        if (isSecured(protocol)) {
            return;
        }
        if (allowInsecure || hasLocalProfile(activeProfiles)) {
            return;
        }
        throw new IllegalStateException(
                "Kafka plaintext is forbidden outside the local profile "
                        + "(set platform.delivery.kafka.security.protocol to SSL, SASL_SSL, or SASL_PLAINTEXT, "
                        + "or use SPRING_PROFILES_ACTIVE=local / platform.delivery.allow-insecure=true for laptop/tests only)");
    }

    static boolean isSecured(String protocolUpper) {
        return "SSL".equals(protocolUpper)
                || "SASL_SSL".equals(protocolUpper)
                || "SASL_PLAINTEXT".equals(protocolUpper);
    }

    private static boolean hasLocalProfile(String[] activeProfiles) {
        if (activeProfiles == null) {
            return false;
        }
        return Arrays.stream(activeProfiles).anyMatch("local"::equals);
    }
}
