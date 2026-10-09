package com.subjex.platform.app.security;

import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.Locale;
import java.util.Objects;

/**
 * PolicyEngineFactory — select {@link SqlRbacPolicyEngine} or {@link CedarPolicyEngine}.
 * <p>
 * Config key {@code platform.authz.engine}: {@code cedar} (default) or {@code sql}.
 * When {@code cedar} is selected but FFI/policies fail to load → fail-closed
 * ({@link IllegalStateException}); does not silently fall back to SQL.
 * AuthZ-1d：默认 Cedar；选 cedar 但原生库不可用则启动失败关闭，不静默回退 SQL。
 */
public final class PolicyEngineFactory {

    public static final String ENGINE_CEDAR = "cedar";
    public static final String ENGINE_SQL = "sql";
    public static final String DEFAULT_ENGINE = ENGINE_CEDAR;

    private PolicyEngineFactory() {}

    /**
     * Build engine from {@code platform.authz.engine}; cedar + missing FFI -> fail-closed startup -
     * 按配置构建引擎；选 cedar 但 FFI 缺失则启动失败关闭。
     *
     * @param engineName raw {@code platform.authz.engine} (null/blank → {@link #DEFAULT_ENGINE})
     */
    public static PolicyEngine create(
            String engineName, TenantGuard tenantGuard, OperatorTenantAccess tenantAccess) {
        Objects.requireNonNull(tenantGuard, "tenantGuard");
        Objects.requireNonNull(tenantAccess, "tenantAccess");
        String normalized = normalize(engineName);
        if (ENGINE_SQL.equals(normalized)) {
            return new SqlRbacPolicyEngine(tenantGuard, tenantAccess);
        }
        if (ENGINE_CEDAR.equals(normalized)) {
            CedarPolicyEngine cedar = new CedarPolicyEngine(tenantGuard, tenantAccess);
            if (!cedar.isNativeAvailable()) {
                throw new IllegalStateException(
                        "platform.authz.engine=cedar but Cedar FFI/policies unavailable: "
                                + cedar.nativeFailureMessage().orElse("unknown")
                                + " — set platform.authz.engine=sql to use SqlRbacPolicyEngine, "
                                + "or fix cedar-java uber natives for this OS/arch");
            }
            return cedar;
        }
        throw new IllegalArgumentException(
                "unknown platform.authz.engine='"
                        + engineName
                        + "' (allowed: cedar, sql)");
    }

    public static String normalize(String engineName) {
        if (engineName == null || engineName.isBlank()) {
            return DEFAULT_ENGINE;
        }
        return engineName.trim().toLowerCase(Locale.ROOT);
    }
}
