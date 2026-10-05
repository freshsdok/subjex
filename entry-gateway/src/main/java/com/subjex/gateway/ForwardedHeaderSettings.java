package com.subjex.gateway;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ForwardedHeaderSettings — 转发头设置：{@code gateway.trusted-proxies}，默认空（不采信 {@code X-Forwarded-For}）。
 * <p>
 * Accepts a YAML list or a comma-separated string, e.g. {@code GATEWAY_TRUSTED_PROXIES=10.0.0.0/8,192.0.2.10}.
 * 可写 YAML 列表或逗号分隔字符串。
 */
@ConfigurationProperties(prefix = "gateway")
public class ForwardedHeaderSettings {

    /** Proxy IPs / CIDRs whose X-Forwarded-For is believed — 其 X-Forwarded-For 可被采信的代理 IP/CIDR。 */
    private List<String> trustedProxies = new ArrayList<>();

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null ? new ArrayList<>() : trustedProxies;
    }
}
