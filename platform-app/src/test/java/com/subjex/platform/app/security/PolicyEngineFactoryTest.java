package com.subjex.platform.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import org.junit.jupiter.api.Test;

/**
 * PolicyEngineFactoryTest — AuthZ-1d engine selection + fail-closed cedar.
 */
class PolicyEngineFactoryTest {

    private final TenantGuard tenantGuard = new DenyWhenTenantMissing();
    private final OperatorTenantAccess tenantAccess = mock(OperatorTenantAccess.class);

    @Test
    void defaultAndBlankSelectCedarWhenFfiAvailable() {
        CedarPolicyEngine probe = new CedarPolicyEngine(tenantGuard, tenantAccess);
        assumeTrue(probe.isNativeAvailable(), () -> probe.nativeFailureMessage().orElse("no ffi"));
        assertInstanceOf(CedarPolicyEngine.class, PolicyEngineFactory.create(null, tenantGuard, tenantAccess));
        assertInstanceOf(CedarPolicyEngine.class, PolicyEngineFactory.create("  ", tenantGuard, tenantAccess));
        assertInstanceOf(CedarPolicyEngine.class, PolicyEngineFactory.create("CEDAR", tenantGuard, tenantAccess));
        assertEquals(PolicyEngineFactory.ENGINE_CEDAR, PolicyEngineFactory.normalize(null));
    }

    @Test
    void sqlSelectsSqlRbac() {
        assertInstanceOf(
                SqlRbacPolicyEngine.class, PolicyEngineFactory.create("sql", tenantGuard, tenantAccess));
        assertInstanceOf(
                SqlRbacPolicyEngine.class, PolicyEngineFactory.create("SQL", tenantGuard, tenantAccess));
    }

    @Test
    void unknownEngineRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PolicyEngineFactory.create("casbin", tenantGuard, tenantAccess));
    }
}
