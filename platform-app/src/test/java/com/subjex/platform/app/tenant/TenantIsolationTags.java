package com.subjex.platform.app.tenant;

/**
 * TenantIsolationTags — TN-3 Surefire group constants for cross-tenant isolation gates.
 * 跨租户隔离门禁的 Surefire 分组常量。
 *
 * <p>Run: {@code mvn -pl platform-app -am test -Dgroups=tenant-isolation
 * -Dsurefire.failIfNoSpecifiedTests=false}
 */
public final class TenantIsolationTags {

    /** JUnit 5 / Surefire group tag — JUnit 5 / Surefire 分组标签. */
    public static final String TENANT_ISOLATION = "tenant-isolation";

    private TenantIsolationTags() {}
}
