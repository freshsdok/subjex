package com.subjex.platform.contract.connection;

import java.util.Locale;

/**
 * ConnectionVendor — 连接厂商：这一进程实际对话的关系库是哪一种。
 * <p>
 * Only MySQL and PostgreSQL are accepted. A third product is a mismatch, not a silent substitute.
 * 只接受 MySQL 和 PostgreSQL。第三种产品是不匹配，不是悄悄的替代品。
 */
public enum ConnectionVendor {
    MYSQL,
    POSTGRESQL;

    /**
     * Map a JDBC product name to a vendor — 把 JDBC 产品名映射到厂商。
     */
    public static ConnectionVendor fromProductName(String productName) {
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("database product name is missing");
        }
        String normalized = productName.toLowerCase(Locale.ROOT);
        if (normalized.contains("mysql")) {
            return MYSQL;
        }
        if (normalized.contains("postgresql")) {
            return POSTGRESQL;
        }
        throw new IllegalArgumentException("unsupported database product: " + productName);
    }

    public static ConnectionVendor parse(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("platform.connection.vendor is missing");
        }
        return ConnectionVendor.valueOf(configured.trim().toUpperCase(Locale.ROOT));
    }
}
