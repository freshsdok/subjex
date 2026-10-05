package com.subjex.platform.app.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.subjex.platform.contract.discovery.InProcessServiceRegistry;
import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

/**
 * Self-registration uses the bound port — 自登记用已绑定的端口。
 * <p>
 * A real Tomcat starts on port 0, so the operating system picks the port; the registry must hold that port.
 * 真实 Tomcat 以端口 0 启动，由操作系统挑端口；登记表里必须是这个端口。
 */
class PlatformSelfRegistrarTest {

    @Test
    void registersTheRandomPortTheServerBound() {
        ServiceRegistry registry = new InProcessServiceRegistry();
        PlatformSelfRegistrar registrar = new PlatformSelfRegistrar(registry, "127.0.0.1");
        // randomPortFactory — 随机端口工厂：端口 0 表示让操作系统挑一个空闲端口。
        TomcatServletWebServerFactory randomPortFactory = new TomcatServletWebServerFactory(0);
        WebServer startedServer = randomPortFactory.getWebServer();
        try {
            startedServer.start();

            registrar.onApplicationEvent(startedEvent(startedServer, null));

            ServiceEndpoint endpoint = registry.resolve(PlatformServiceNames.PLATFORM_APP).orElseThrow();
            assertEquals("127.0.0.1", endpoint.host());
            assertNotEquals(0, endpoint.port());
            assertEquals(startedServer.getPort(), endpoint.port());
        } finally {
            startedServer.stop();
        }
    }

    @Test
    void ignoresTheSeparateManagementServer() {
        ServiceRegistry registry = new InProcessServiceRegistry();
        PlatformSelfRegistrar registrar = new PlatformSelfRegistrar(registry, "127.0.0.1");
        WebServer managementServer = mock(WebServer.class);
        when(managementServer.getPort()).thenReturn(18081);

        registrar.onApplicationEvent(
                startedEvent(managementServer, PlatformSelfRegistrar.MANAGEMENT_SERVER_NAMESPACE));

        assertTrue(registry.resolve(PlatformServiceNames.PLATFORM_APP).isEmpty());
    }

    /** startedEvent — 已启动事件：模拟 Spring Boot 在服务器开始监听后发出的事件。 */
    private static WebServerInitializedEvent startedEvent(WebServer startedServer, String serverNamespace) {
        WebServerApplicationContext owningContext = mock(WebServerApplicationContext.class);
        when(owningContext.getServerNamespace()).thenReturn(serverNamespace);
        return new WebServerInitializedEvent(startedServer) {
            @Override
            public WebServerApplicationContext getApplicationContext() {
                return owningContext;
            }
        };
    }
}
