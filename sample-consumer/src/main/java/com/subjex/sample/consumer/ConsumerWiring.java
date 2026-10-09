package com.subjex.sample.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.contract.config.ConfigSource;
import com.subjex.platform.contract.delivery.OutboxTls;
import com.subjex.platform.contract.delivery.OutboxTransportPolicy;
import com.subjex.platform.contract.delivery.SecretPlaceholderGuard;
import com.subjex.platform.contract.config.HttpConfigSource;
import com.subjex.platform.contract.config.LocalApplicationConfig;
import com.subjex.platform.contract.config.OverridingConfigSource;
import com.subjex.platform.contract.discovery.FallbackServiceRegistry;
import com.subjex.platform.contract.discovery.HttpServiceRegistry;
import com.subjex.platform.contract.discovery.InProcessServiceRegistry;
import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRegistry;
import com.subjex.platform.contract.discovery.StaticServiceFallback;
import com.subjex.sample.consumer.discovery.ConsumerSelfRegistrar;
import com.subjex.sample.consumer.discovery.PlatformAppResolver;
import io.opentelemetry.api.OpenTelemetry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * ConsumerWiring — 消费者装配：经 HTTP 登记自己并解析 platform-app，同时监听出箱套接字。
 */
@Configuration
public class ConsumerWiring {

    /**
     * Prefer the effective value on platform-app over HTTP. When that call cannot connect, use local application config.
     * 优先用 HTTP 读 platform-app 上的生效值。连不上时用本地应用配置。
     */
    @Bean
    ConfigSource configSource(
            Environment environment,
            @Value("${spring.security.user.name}") String username,
            @Value("${spring.security.user.password}") String password) {
        LocalApplicationConfig local = new LocalApplicationConfig(key -> environment.getProperty(key));
        StaticServiceFallback fallback =
                StaticServiceFallback.fromConfig(local, PlatformServiceNames.PLATFORM_APP);
        Optional<ServiceEndpoint> platform = fallback.resolve(PlatformServiceNames.PLATFORM_APP);
        if (platform.isEmpty()) {
            return local;
        }
        HttpConfigSource remote = new HttpConfigSource(
                URI.create("http://" + platform.get().host() + ":" + platform.get().port()),
                username,
                password,
                Duration.ofMillis(800));
        return new OverridingConfigSource(remote, local);
    }

    /**
     * The primary registry is HTTP on the static platform-app address.
     * When that address is not configured, an empty in-process table leaves resolve to fail closed.
     * 主登记簿是静态 platform-app 地址上的 HTTP。
     * 没有配置那个地址时，空的进程内表让解析失败关闭。
     */
    @Bean
    ServiceRegistry serviceRegistry(
            ConfigSource configSource,
            @Value("${spring.security.user.name}") String username,
            @Value("${spring.security.user.password}") String password) {
        StaticServiceFallback fallback =
                StaticServiceFallback.fromConfig(configSource, PlatformServiceNames.PLATFORM_APP);
        Optional<ServiceEndpoint> platform = fallback.resolve(PlatformServiceNames.PLATFORM_APP);
        ServiceRegistry primary = platform
                .<ServiceRegistry>map(endpoint -> new HttpServiceRegistry(
                        URI.create("http://" + endpoint.host() + ":" + endpoint.port()),
                        username,
                        password,
                        Duration.ofMillis(800)))
                .orElseGet(InProcessServiceRegistry::new);
        return new FallbackServiceRegistry(primary, fallback);
    }

    /**
     * Resolve platform-app when the context starts. An unreachable HTTP registry uses static config.
     * 上下文启动时解析 platform-app。HTTP 登记簿连不上时使用静态配置。
     */
    @Bean
    PlatformAppResolver platformAppResolver(ServiceRegistry serviceRegistry) {
        return new PlatformAppResolver(serviceRegistry);
    }

    @Bean
    ConsumerSelfRegistrar consumerSelfRegistrar(
            ServiceRegistry serviceRegistry,
            @Value("${platform.discovery.self-host:127.0.0.1}") String host,
            WebServerApplicationContext web) {
        return new ConsumerSelfRegistrar(serviceRegistry, host, () -> web.getWebServer().getPort());
    }

    @Bean
    TaskRecordedReceipts taskRecordedReceipts() {
        return new TaskRecordedReceipts();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OutboxSocketListener outboxSocketListener(
            @Value("${platform.delivery.listen-port:0}") int listenPort,
            @Value("${platform.delivery.hmac-secret}") String hmacSecret,
            @Value("${platform.delivery.hmac-secret-previous:}") String hmacSecretPrevious,
            @Value("${platform.delivery.tls.enabled:false}") boolean tlsEnabled,
            @Value("${platform.delivery.tls.keystore-path:}") String keystorePath,
            @Value("${platform.delivery.tls.keystore-password:}") String keystorePassword,
            @Value("${platform.delivery.allow-insecure:false}") boolean allowInsecure,
            Environment environment,
            OpenTelemetry openTelemetry,
            ObjectMapper objectMapper,
            TaskRecordedReceipts receipts,
            Clock clock) {
        SecretPlaceholderGuard.refusePlaceholdersOutsideLocal(
                environment.getActiveProfiles(), "OUTBOX_HMAC_SECRET", hmacSecret, true);
        SecretPlaceholderGuard.refusePlaceholdersOutsideLocal(
                environment.getActiveProfiles(), "OUTBOX_HMAC_SECRET_PREVIOUS", hmacSecretPrevious, false);
        OutboxTransportPolicy.requireReady(hmacSecret, tlsEnabled, allowInsecure, environment.getActiveProfiles());
        SSLContext ssl = null;
        if (tlsEnabled) {
            if (keystorePath == null || keystorePath.isBlank()) {
                throw new IllegalStateException("platform.delivery.tls.keystore-path is required when TLS is enabled");
            }
            ssl = OutboxTls.serverContext(
                    Path.of(keystorePath),
                    keystorePassword == null ? new char[0] : keystorePassword.toCharArray());
        }
        return new OutboxSocketListener(
                listenPort, hmacSecret, hmacSecretPrevious, ssl, openTelemetry, objectMapper, receipts, clock);
    }
}
