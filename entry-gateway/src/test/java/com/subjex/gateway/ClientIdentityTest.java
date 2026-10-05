package com.subjex.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * ClientIdentityTest — 客户端标识测试：转发头优先，否则远端地址。
 */
class ClientIdentityTest {

    @Test
    void prefersFirstForwardedAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        request.setRemoteAddr("127.0.0.1");
        assertEquals("203.0.113.9", ClientIdentity.from(request));
    }

    @Test
    void fallsBackToRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.4");
        assertEquals("192.0.2.4", ClientIdentity.from(request));
    }
}
