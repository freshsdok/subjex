package com.subjex.platform.contract.delivery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OutboxTransportPolicyTest {

    private static final String SECRET = "a".repeat(32);

    @Test
    void nonLocalWithoutTlsFailsClosed() {
        assertThrows(
                IllegalStateException.class,
                () -> OutboxTransportPolicy.requireReady(SECRET, false, false, new String[] {"prod"}));
    }

    @Test
    void localProfileAllowsPlaintextWhenHmacPresent() {
        assertDoesNotThrow(() -> OutboxTransportPolicy.requireReady(SECRET, false, false, new String[] {"local"}));
    }

    @Test
    void allowInsecureAllowsPlaintextForTests() {
        assertDoesNotThrow(() -> OutboxTransportPolicy.requireReady(SECRET, false, true, new String[] {}));
    }

    @Test
    void tlsEnabledPassesWithoutLocal() {
        assertDoesNotThrow(() -> OutboxTransportPolicy.requireReady(SECRET, true, false, new String[] {}));
    }

    @Test
    void missingHmacFails() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboxTransportPolicy.requireReady("short", true, false, new String[] {}));
    }
}
