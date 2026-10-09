package com.subjex.platform.contract.delivery;

/**
 * DeliveryTransport — 出箱传输单选：一个进程只启用一种。
 * <p>
 * {@code socket} is the local/quickstart demo (SUBJEX-OUTBOX 2). {@code kafka} is the optional bus adapter
 * (separate module, not on the default classpath).
 * {@code socket} 是本机/快速开始演示。{@code kafka} 是可选总线适配器（独立模块，默认不进 classpath）。
 */
public enum DeliveryTransport {
    SOCKET,
    KAFKA;

    /**
     * Parse config value {@code platform.delivery.transport} — 解析配置值。
     *
     * @throws IllegalArgumentException when the value is blank or unknown
     */
    public static DeliveryTransport parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("platform.delivery.transport is missing");
        }
        String normalized = raw.trim().toLowerCase();
        return switch (normalized) {
            case "socket" -> SOCKET;
            case "kafka" -> KAFKA;
            default -> throw new IllegalArgumentException(
                    "platform.delivery.transport must be socket|kafka, got: " + raw.trim());
        };
    }
}
