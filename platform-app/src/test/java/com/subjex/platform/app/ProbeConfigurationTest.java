package com.subjex.platform.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProbeConfigurationTest {

    @Test
    void actuatorProbesAreEnabled() throws Exception {
        try (var in = ProbeConfigurationTest.class.getResourceAsStream("/application.yml")) {
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yaml.contains("probes:"));
            assertTrue(yaml.contains("livenessstate:"));
            assertTrue(yaml.contains("readinessstate:"));
            assertTrue(yaml.contains("enabled: true"));
        }
    }
}
