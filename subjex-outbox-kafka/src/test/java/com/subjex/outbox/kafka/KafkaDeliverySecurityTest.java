package com.subjex.outbox.kafka;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class KafkaDeliverySecurityTest {

    @Test
    void securedProtocolsAreAlwaysAllowed() {
        assertDoesNotThrow(() -> KafkaDeliverySecurity.requireReady("SSL", false, new String[] {}));
        assertDoesNotThrow(() -> KafkaDeliverySecurity.requireReady("SASL_SSL", false, new String[] {}));
        assertDoesNotThrow(() -> KafkaDeliverySecurity.requireReady("SASL_PLAINTEXT", false, new String[] {}));
    }

    @Test
    void plaintextForbiddenOutsideLocal() {
        assertThrows(
                IllegalStateException.class,
                () -> KafkaDeliverySecurity.requireReady("PLAINTEXT", false, new String[] {"prod"}));
    }

    @Test
    void plaintextAllowedUnderLocalOrAllowInsecure() {
        assertDoesNotThrow(() -> KafkaDeliverySecurity.requireReady("PLAINTEXT", false, new String[] {"local"}));
        assertDoesNotThrow(() -> KafkaDeliverySecurity.requireReady("PLAINTEXT", true, new String[] {}));
    }
}
