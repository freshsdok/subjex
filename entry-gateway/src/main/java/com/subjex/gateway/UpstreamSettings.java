package com.subjex.gateway;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * UpstreamSettings — 上游设置：platform-app 的根地址。
 */
@ConfigurationProperties(prefix = "gateway.upstream")
public class UpstreamSettings {

    /** Root URL of platform-app, no trailing slash — platform-app 根地址，不要末尾斜杠。 */
    private String baseUrl = "http://127.0.0.1:8080";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public URI root() {
        String trimmed = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        return URI.create(trimmed);
    }
}
