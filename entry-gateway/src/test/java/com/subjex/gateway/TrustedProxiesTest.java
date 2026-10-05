package com.subjex.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TrustedProxiesTest — 可信代理测试：IP/CIDR 匹配、空配置不信任任何人、非字面量拒绝。
 */
class TrustedProxiesTest {

    @Test
    void emptyTrustsNobody() {
        assertTrue(TrustedProxies.of(List.of()).isEmpty());
        assertTrue(TrustedProxies.of(null).isEmpty());
        assertTrue(TrustedProxies.of(Arrays.asList("", "  ", null)).isEmpty());
        assertFalse(TrustedProxies.none().contains("127.0.0.1"));
    }

    @Test
    void matchesIpv4CidrAndSingleAddress() {
        TrustedProxies proxies = TrustedProxies.of(List.of("10.0.0.0/8", " 192.0.2.10 ", "172.16.0.0/12"));
        assertTrue(proxies.contains("10.255.0.1"));
        assertTrue(proxies.contains("192.0.2.10"));
        assertTrue(proxies.contains("172.31.255.255"));
        assertFalse(proxies.contains("172.32.0.1"));
        assertFalse(proxies.contains("192.0.2.11"));
        assertFalse(proxies.contains("11.0.0.1"));
    }

    @Test
    void matchesIpv6AndMappedIpv4() {
        TrustedProxies proxies = TrustedProxies.of(List.of("::1", "fd00::/8", "10.0.0.0/8"));
        assertTrue(proxies.contains("0:0:0:0:0:0:0:1"));
        assertTrue(proxies.contains("[::1]"));
        assertTrue(proxies.contains("fd12:3456::1"));
        assertTrue(proxies.contains("::ffff:10.1.2.3"));
        assertFalse(proxies.contains("2001:db8::1"));
    }

    @Test
    void nonLiteralsNeverMatch() {
        TrustedProxies proxies = TrustedProxies.of(List.of("0.0.0.0/0"));
        assertTrue(proxies.contains("203.0.113.9"));
        assertFalse(proxies.contains("localhost"));
        assertFalse(proxies.contains("999.1.1.1"));
        assertFalse(proxies.contains("unknown"));
        assertFalse(proxies.contains(null));
    }

    @Test
    void rejectsBadEntries() {
        assertThrows(IllegalArgumentException.class, () -> TrustedProxies.of(List.of("proxy.internal")));
        assertThrows(IllegalArgumentException.class, () -> TrustedProxies.of(List.of("10.0.0.0/33")));
        assertThrows(IllegalArgumentException.class, () -> TrustedProxies.of(List.of("10.0.0.0/x")));
        assertThrows(IllegalArgumentException.class, () -> TrustedProxies.of(List.of("300.0.0.1")));
    }
}
