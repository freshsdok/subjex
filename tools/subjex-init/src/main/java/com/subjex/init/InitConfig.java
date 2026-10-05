package com.subjex.init;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * InitConfig — 要写入 stub 的取值（厂商、JDBC、是否附 Redis 提示）。
 */
public final class InitConfig {

    public static final String DEFAULT_VENDOR = "postgresql";
    public static final String DEFAULT_USER = "subjex";
    public static final String DEFAULT_PASSWORD = "subjex";

    private final Path targetDir;
    private final String vendor;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final boolean includeRedisNotes;

    public InitConfig(
            Path targetDir,
            String vendor,
            String jdbcUrl,
            String username,
            String password,
            boolean includeRedisNotes) {
        this.targetDir = Objects.requireNonNull(targetDir, "targetDir");
        this.vendor = normalizeVendor(vendor);
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.includeRedisNotes = includeRedisNotes;
    }

    public static InitConfig defaults(Path targetDir) {
        String vendor = DEFAULT_VENDOR;
        return new InitConfig(
                targetDir,
                vendor,
                defaultJdbcUrl(vendor),
                DEFAULT_USER,
                DEFAULT_PASSWORD,
                true);
    }

    public static String defaultJdbcUrl(String vendor) {
        String v = normalizeVendor(vendor);
        if ("mysql".equals(v)) {
            return "jdbc:mysql://127.0.0.1:3306/subjex?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        }
        return "jdbc:postgresql://127.0.0.1:5432/subjex";
    }

    public static String defaultDriver(String vendor) {
        return "mysql".equals(normalizeVendor(vendor))
                ? "com.mysql.cj.jdbc.Driver"
                : "org.postgresql.Driver";
    }

    public static String normalizeVendor(String vendor) {
        if (vendor == null || vendor.isBlank()) {
            return DEFAULT_VENDOR;
        }
        String v = vendor.trim().toLowerCase(Locale.ROOT);
        if (!v.equals("postgresql") && !v.equals("mysql")) {
            throw new IllegalArgumentException("vendor must be postgresql or mysql, got: " + vendor);
        }
        return v;
    }

    public Path targetDir() {
        return targetDir;
    }

    public String vendor() {
        return vendor;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public boolean includeRedisNotes() {
        return includeRedisNotes;
    }

    public String driver() {
        return defaultDriver(vendor);
    }
}
