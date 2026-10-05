package com.subjex.platform.app;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The optional model gateway is not on this classpath — 可选模型网关不在这条 classpath 上。
 * <p>
 * platform-app does not depend on model-gateway, so the host still starts when that module is absent.
 * platform-app 不依赖 model-gateway，因此该模块不在时宿主仍然可以启动。
 */
class ModelGatewayAbsentTest {

    @Test
    void optionalGatewayIsNotOnThePlatformClasspath() {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("com.subjex.model.gateway.RegisteredModelGateway"));
    }
}
