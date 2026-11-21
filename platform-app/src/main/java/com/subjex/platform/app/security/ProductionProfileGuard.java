package com.subjex.platform.app.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * ProductionProfileGuard — 生产配置与 {@code local} 互斥：同时启用则启动失败。
 * <p>
 * Fails fast when a dedicated production profile ({@code prod} or {@code production}) is active
 * together with {@code local}. Local operator seeds must not run under production configuration;
 * silent ignore would hide a misconfiguration, so startup is refused.
 * 当专用生产 profile（{@code prod} 或 {@code production}）与 {@code local} 同时激活时立即失败。
 * 本地操作员种子不得在生产配置下运行；静默忽略会掩盖错误配置，因此拒绝启动。
 */
public final class ProductionProfileGuard {

    /** Laptop / quickstart profile — 本机与快速开始 profile。 */
    public static final String LOCAL_PROFILE = "local";

    /** Dedicated production profile names recognized by this guard — 本门禁识别的专用生产 profile 名。 */
    public static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    private ProductionProfileGuard() {}

    public static boolean isLocalProfile(String[] activeProfiles) {
        return containsProfile(activeProfiles, LOCAL_PROFILE);
    }

    public static boolean isProductionProfile(String[] activeProfiles) {
        if (activeProfiles == null) {
            return false;
        }
        return Arrays.stream(activeProfiles).anyMatch(ProductionProfileGuard::isProductionProfileName);
    }

    public static boolean isProductionProfileName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return PRODUCTION_PROFILES.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Refuse a start that would combine production configuration with {@code local}.
     * 拒绝把生产配置与 {@code local} 组合在一起的启动。
     */
    public static void assertCompatible(String[] activeProfiles) {
        if (isLocalProfile(activeProfiles) && isProductionProfile(activeProfiles)) {
            throw new IllegalStateException(
                    "Production profile (prod/production) cannot be combined with the local profile. "
                            + "Refuse startup so LocalOperatorSeeder cannot run under production configuration. "
                            + "生产配置（prod/production）不可与 local 同时启用。"
                            + "启动已拒绝，避免 LocalOperatorSeeder 在生产配置下种入本地账号。");
        }
    }

    private static boolean containsProfile(String[] activeProfiles, String expected) {
        if (activeProfiles == null) {
            return false;
        }
        return Arrays.stream(activeProfiles).anyMatch(expected::equals);
    }
}
