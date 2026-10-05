package com.subjex.model.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RegisteredModelGatewayTest {

    @Test
    void registersAProviderAndRecordsOneInvocation() {
        RegisteredModelGateway gateway = new RegisteredModelGateway();
        gateway.register("provider-local", "model-echo");

        ModelInvocation invocation = gateway.record("provider-local", "model-echo", "digest-9f2c");

        assertEquals("provider-local", invocation.providerId());
        assertEquals("model-echo", invocation.modelId());
        assertEquals("digest-9f2c", invocation.inputDigest());
        assertEquals(invocation, gateway.invocation().orElseThrow());
    }

    @Test
    void unregisteredProviderIsRefused() {
        RegisteredModelGateway gateway = new RegisteredModelGateway();
        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.record("provider-local", "model-echo", "digest-9f2c"));
        assertTrue(gateway.invocation().isEmpty());
    }
}
