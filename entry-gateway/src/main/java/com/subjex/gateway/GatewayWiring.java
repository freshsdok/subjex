package com.subjex.gateway;

import com.subjex.platform.contract.ratelimit.RateLimitPort;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * GatewayWiring — 网关装配：时钟、限流端口、客户端标识、上游 HTTP 客户端。
 */
@Configuration
@EnableConfigurationProperties({UpstreamSettings.class, ForwardedHeaderSettings.class})
public class GatewayWiring {

    /** Action name used for every forwarded HTTP call — 每次转发 HTTP 调用使用的动作名。 */
    public static final String HTTP_ACTION = "http";

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RateLimitPort rateLimitPort(
            @Value("${gateway.rate-limit.permits:120}") int permits,
            @Value("${gateway.rate-limit.window-seconds:60}") int windowSeconds,
            Clock clock) {
        return new GatewayRateLimit(permits, Duration.ofSeconds(windowSeconds), clock);
    }

    @Bean
    ClientIdentity clientIdentity(ForwardedHeaderSettings settings) {
        return new ClientIdentity(TrustedProxies.of(settings.getTrustedProxies()));
    }

    @Bean
    HttpClient upstreamHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
}
