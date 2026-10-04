package com.subjex.platform.contract.tenant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DenyWhenTenantMissingTest {

    private final TenantGuard guard = new DenyWhenTenantMissing();

    @Test
    void blankTenantIsDenied() {
        assertThrows(TenantMissingException.class, () -> guard.requireTenant(null));
        assertThrows(TenantMissingException.class, () -> guard.requireTenant(""));
        assertThrows(TenantMissingException.class, () -> guard.requireTenant("   "));
    }

    @Test
    void namedTenantIsAllowed() {
        assertDoesNotThrow(() -> guard.requireTenant("tenant-north"));
    }
}
