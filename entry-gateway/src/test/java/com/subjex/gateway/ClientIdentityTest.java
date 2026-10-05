package com.subjex.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * ClientIdentityTest — 客户端标识测试：未配置可信代理时忽略转发头；直连方是可信代理时从右往左取第一个不可信跳。
 */
class ClientIdentityTest {

    private static final ClientIdentity DEFAULT = new ClientIdentity(TrustedProxies.none());
    private static final ClientIdentity BEHIND_PROXY =
            new ClientIdentity(TrustedProxies.of(List.of("10.0.0.0/8", "192.0.2.10")));

    @Test
    void ignoresForwardedHeaderByDefault() {
        MockHttpServletRequest request = request("198.51.100.7", "203.0.113.9");
        assertEquals("198.51.100.7", DEFAULT.resolve(request));
    }

    @Test
    void fallsBackToRemoteAddress() {
        MockHttpServletRequest request = request("192.0.2.4", null);
        assertEquals("192.0.2.4", DEFAULT.resolve(request));
        assertEquals("192.0.2.4", BEHIND_PROXY.resolve(request));
    }

    @Test
    void ignoresForwardedHeaderFromUntrustedPeer() {
        MockHttpServletRequest request = request("198.51.100.7", "203.0.113.9");
        assertEquals("198.51.100.7", BEHIND_PROXY.resolve(request));
    }

    @Test
    void usesForwardedAddressFromTrustedPeer() {
        MockHttpServletRequest request = request("10.1.2.3", "203.0.113.9");
        assertEquals("203.0.113.9", BEHIND_PROXY.resolve(request));
    }

    @Test
    void skipsTrustedHopsAndIgnoresSpoofedLeftEntries() {
        // Client sent "X-Forwarded-For: 1.2.3.4"; the edge proxy appended the real peer, then an inner proxy.
        MockHttpServletRequest request = request("10.0.0.5", "1.2.3.4, 203.0.113.9, 192.0.2.10");
        assertEquals("203.0.113.9", BEHIND_PROXY.resolve(request));
    }

    @Test
    void readsRepeatedHeaderLinesInOrder() {
        MockHttpServletRequest request = request("10.0.0.5", "1.2.3.4");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals("203.0.113.9", BEHIND_PROXY.resolve(request));
    }

    @Test
    void trustedPeerWithoutHeaderUsesPeer() {
        assertEquals("10.0.0.5", BEHIND_PROXY.resolve(request("10.0.0.5", null)));
        assertEquals("10.0.0.5", BEHIND_PROXY.resolve(request("10.0.0.5", " , ")));
    }

    @Test
    void allHopsTrustedUsesLeftMost() {
        MockHttpServletRequest request = request("10.0.0.5", "10.9.9.9, 192.0.2.10");
        assertEquals("10.9.9.9", BEHIND_PROXY.resolve(request));
    }

    private static MockHttpServletRequest request(String remote, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }
}
