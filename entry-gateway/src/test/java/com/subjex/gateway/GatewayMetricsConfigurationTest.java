package com.subjex.gateway;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * GatewayMetricsConfigurationTest — 网关指标配置测试：Prometheus 端点已暴露。
 */
class GatewayMetricsConfigurationTest {

    @Test
    void prometheusIsExposed() throws Exception {
        try (var in = GatewayMetricsConfigurationTest.class.getResourceAsStream("/application.yml")) {
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yaml.contains("prometheus"));
            assertTrue(yaml.contains("health,prometheus") || yaml.contains("prometheus,health"));
        }
    }
}
