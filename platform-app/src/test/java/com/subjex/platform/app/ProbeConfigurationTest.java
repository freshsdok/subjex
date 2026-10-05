package com.subjex.platform.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * ProbeConfigurationTest — 探针与指标配置测试：存活/就绪与 Prometheus 端点都在说明里打开。
 */
class ProbeConfigurationTest {

    @Test
    void actuatorProbesAndPrometheusAreEnabled() throws Exception {
        try (var in = ProbeConfigurationTest.class.getResourceAsStream("/application.yml")) {
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yaml.contains("probes:"));
            assertTrue(yaml.contains("livenessstate:"));
            assertTrue(yaml.contains("readinessstate:"));
            assertTrue(yaml.contains("enabled: true"));
            assertTrue(yaml.contains("prometheus"));
            assertTrue(yaml.contains("health,prometheus") || yaml.contains("prometheus,health"));
        }
    }
}
