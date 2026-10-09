package com.subjex.platform.contract.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DeliveryTransportTest {

    @Test
    void parseAcceptsSocketAndKafka() {
        assertEquals(DeliveryTransport.SOCKET, DeliveryTransport.parse("socket"));
        assertEquals(DeliveryTransport.SOCKET, DeliveryTransport.parse(" SOCKET "));
        assertEquals(DeliveryTransport.KAFKA, DeliveryTransport.parse("kafka"));
    }

    @Test
    void parseRejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> DeliveryTransport.parse("nats"));
        assertThrows(IllegalArgumentException.class, () -> DeliveryTransport.parse(""));
        assertThrows(IllegalArgumentException.class, () -> DeliveryTransport.parse(null));
    }
}
