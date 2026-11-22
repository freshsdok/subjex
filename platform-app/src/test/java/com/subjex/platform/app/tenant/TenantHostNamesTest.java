package com.subjex.platform.app.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * TenantHostNamesTest — normalize, reject invalid names, ignore X-Forwarded-Host.
 * 规范化、拒绝非法名称，并且不采信 X-Forwarded-Host。
 */
class TenantHostNamesTest {

    @Test
    void normalizesCasePortAndTrailingDot() {
        assertThat(TenantHostNames.normalize("APP.Acme.COM:443")).isEqualTo("app.acme.com");
        assertThat(TenantHostNames.normalize("app.acme.com.")).isEqualTo("app.acme.com");
        assertThat(TenantHostNames.normalize(" [ ::1 ]:8080 ")).isEqualTo("::1");
        assertThat(TenantHostNames.normalize("::1")).isEqualTo("::1");
        assertThat(TenantHostNames.normalize("http://app.acme.com")).isNull();
        assertThat(TenantHostNames.normalize("app.acme.com/path")).isNull();
        assertThat(TenantHostNames.normalize("*.acme.com")).isNull();
        assertThat(TenantHostNames.normalize("")).isNull();
    }

    @Test
    void dnsHostRequiresTwoSafeLabels() {
        assertThat(TenantHostNames.isValidDnsHostname("app.acme.com")).isTrue();
        assertThat(TenantHostNames.isValidDnsHostname("shop-1.example.co.uk")).isTrue();
        assertThat(TenantHostNames.isValidDnsHostname("localhost")).isFalse();
        assertThat(TenantHostNames.isValidDnsHostname("-bad.example.com")).isFalse();
        assertThat(TenantHostNames.isValidDnsHostname("bad-.example.com")).isFalse();
        assertThat(TenantHostNames.isValidDnsHostname("127.0.0.1")).isFalse();
        assertThat(TenantHostNames.isValidDnsHostname("app_host.example.com")).isFalse();
    }

    @Test
    void loopbackAndConfiguredNamesAreReserved() {
        Set<String> reserved = TenantHostNames.reservedHosts("api.example.com, Console.Example.COM:443");
        assertThat(TenantHostNames.isReservedPlatformHost("localhost", reserved)).isTrue();
        assertThat(TenantHostNames.isReservedPlatformHost("127.0.0.1", reserved)).isTrue();
        assertThat(TenantHostNames.isReservedPlatformHost("::1", reserved)).isTrue();
        assertThat(TenantHostNames.isReservedPlatformHost("api.example.com", reserved)).isTrue();
        assertThat(TenantHostNames.isReservedPlatformHost("console.example.com", reserved)).isTrue();
        assertThat(TenantHostNames.isReservedPlatformHost("shop.acme.com", reserved)).isFalse();
    }

    @Test
    void requestHostIgnoresForwardedHostAndRejectsDistinctDuplicates() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Host", "localhost:8080");
        request.addHeader("X-Forwarded-Host", "shop.acme.com");
        assertThat(TenantHostNames.hostsFromRequest(request)).containsExactly("localhost");

        MockHttpServletRequest clash = new MockHttpServletRequest();
        clash.addHeader("Host", "shop.acme.com");
        clash.addHeader("Host", "other.acme.com");
        List<String> hosts = TenantHostNames.hostsFromRequest(clash);
        assertThat(hosts).containsExactly("shop.acme.com", "other.acme.com");
        assertThatThrownBy(() -> TenantHostNames.requireSingleHost(hosts))
                .isInstanceOf(TenantHostAmbiguousException.class);
    }
}
