package com.subjex.platform.contract.delivery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecretPlaceholderGuardTest {

    @Test
    void localAllowsChangeMe() {
        assertDoesNotThrow(() -> SecretPlaceholderGuard.refusePlaceholdersOutsideLocal(
                new String[] {"local"}, "OUTBOX_HMAC_SECRET", "change-me-outbox-hmac-secret-local!!", true));
    }

    @Test
    void nonLocalRejectsChangeMe() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> SecretPlaceholderGuard.refusePlaceholdersOutsideLocal(
                        new String[] {}, "OUTBOX_HMAC_SECRET", "change-me-outbox-hmac-secret-local!!", true));
        assertTrue(ex.getMessage().contains("change-me"));
    }

    @Test
    void nonLocalAcceptsStrongSecret() {
        assertDoesNotThrow(() -> SecretPlaceholderGuard.refusePlaceholdersOutsideLocal(
                new String[] {"prod"},
                "OUTBOX_HMAC_SECRET",
                "a-real-secret-with-enough-length!!!!",
                true));
    }
}
