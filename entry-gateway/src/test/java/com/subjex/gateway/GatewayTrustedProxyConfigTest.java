package com.subjex.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * GatewayTrustedProxyConfigTest — 配置 {@code gateway.trusted-proxies}（逗号分隔）后，可信直连方的转发头才被采信；
 * Tomcat 的转发头处理保持关闭。
 */
@SpringBootTest(properties = {
    "gateway.trusted-proxies=10.0.0.0/8, 192.0.2.10",
    "gateway.upstream.base-url=http://127.0.0.1:9"
})
class GatewayTrustedProxyConfigTest {

    @Autowired
    private ClientIdentity clientIdentity;

    @Autowired
    private ServerProperties serverProperties;

    @Test
    void tomcatRemoteIpValveStaysOff() {
        // Otherwise Spring Boot enables it under Kubernetes and rewrites the remote address from the header.
        assertEquals(ServerProperties.ForwardHeadersStrategy.NONE, serverProperties.getForwardHeadersStrategy());
    }

    @Test
    void bindsCommaSeparatedTrustedProxies() {
        MockHttpServletRequest fromProxy = new MockHttpServletRequest();
        fromProxy.setRemoteAddr("192.0.2.10");
        fromProxy.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals("203.0.113.9", clientIdentity.resolve(fromProxy));

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("198.51.100.7");
        direct.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals("198.51.100.7", clientIdentity.resolve(direct));
    }
}
