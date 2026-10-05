package com.subjex.sample.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.contract.config.ConfigSource;
import com.subjex.platform.contract.config.LocalApplicationConfig;
import com.subjex.platform.contract.discovery.FallbackServiceRegistry;
import com.subjex.platform.contract.discovery.InProcessServiceRegistry;
import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.ServiceRegistry;
import com.subjex.platform.contract.discovery.StaticServiceFallback;
import com.subjex.sample.consumer.discovery.PlatformAppResolver;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * ConsumerWiring — 消费者装配：解析 platform-app，并监听出箱套接字。
 */
@Configuration
public class ConsumerWiring {

    @Bean
    ConfigSource configSource(Environment environment) {
        return new LocalApplicationConfig(key -> environment.getProperty(key));
    }

    @Bean
    ServiceRegistry serviceRegistry(ConfigSource configSource) {
        return new FallbackServiceRegistry(
                new InProcessServiceRegistry(),
                StaticServiceFallback.fromConfig(configSource, PlatformServiceNames.PLATFORM_APP));
    }

    /**
     * Resolve platform-app when the context starts. An empty in-process registry uses static config.
     * 上下文启动时解析 platform-app。空的进程内登记簿使用静态配置。
     */
    @Bean
    PlatformAppResolver platformAppResolver(ServiceRegistry serviceRegistry) {
        return new PlatformAppResolver(serviceRegistry);
    }

    @Bean
    TaskRecordedReceipts taskRecordedReceipts() {
        return new TaskRecordedReceipts();
    }

    @Bean
    OutboxSocketListener outboxSocketListener(
            @Value("${platform.delivery.listen-port:0}") int listenPort,
            @Value("${platform.delivery.operator-name}") String operatorName,
            @Value("${platform.delivery.operator-password}") String operatorPassword,
            OpenTelemetry openTelemetry,
            ObjectMapper objectMapper,
            TaskRecordedReceipts receipts) {
        return new OutboxSocketListener(
                listenPort, operatorName, operatorPassword, openTelemetry, objectMapper, receipts);
    }
}
