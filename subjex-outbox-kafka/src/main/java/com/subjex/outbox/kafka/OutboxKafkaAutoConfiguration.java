package com.subjex.outbox.kafka;

import com.subjex.platform.contract.delivery.DeliveryCircuitBreaker;
import com.subjex.platform.contract.delivery.DeliveryPort;
import io.opentelemetry.api.OpenTelemetry;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * OutboxKafkaAutoConfiguration — 仅当 {@code platform.delivery.transport=kafka} 且本模块在 classpath 时装配。
 * <p>
 * Not on the default platform-app startup path. Missing bootstrap servers or topic refuses start.
 * Non-{@code local} forbids plaintext (TLS or SASL required).
 * 默认不进 platform-app 启动路径。缺主机或主题拒绝启动；非 local 禁止明文。
 */
@AutoConfiguration
@ConditionalOnClass(KafkaProducer.class)
@ConditionalOnProperty(name = "platform.delivery.transport", havingValue = "kafka")
public class OutboxKafkaAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "outboxKafkaProducer")
    Producer<String, byte[]> outboxKafkaProducer(
            @Value("${platform.delivery.kafka.bootstrap-servers:}") String bootstrapServers,
            @Value("${platform.delivery.kafka.security.protocol:PLAINTEXT}") String securityProtocol,
            @Value("${platform.delivery.kafka.sasl.mechanism:}") String saslMechanism,
            @Value("${platform.delivery.kafka.sasl.jaas-config:}") String saslJaasConfig,
            @Value("${platform.delivery.allow-insecure:false}") boolean allowInsecure,
            Environment environment) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalStateException("platform.delivery.kafka.bootstrap-servers is required when transport=kafka");
        }
        KafkaDeliverySecurity.requireReady(securityProtocol, allowInsecure, environment.getActiveProfiles());
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers.trim());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "subjex-outbox");
        String protocol = securityProtocol == null || securityProtocol.isBlank() ? "PLAINTEXT" : securityProtocol.trim();
        props.put("security.protocol", protocol);
        if (saslMechanism != null && !saslMechanism.isBlank()) {
            props.put("sasl.mechanism", saslMechanism.trim());
        }
        if (saslJaasConfig != null && !saslJaasConfig.isBlank()) {
            props.put("sasl.jaas.config", saslJaasConfig);
        }
        return new KafkaProducer<>(props);
    }

    @Bean
    @ConditionalOnMissingBean(DeliveryPort.class)
    DeliveryPort kafkaDeliveryPort(
            Producer<String, byte[]> outboxKafkaProducer,
            @Value("${platform.delivery.kafka.topic:}") String topic,
            @Value("${platform.delivery.declaration-version:1}") int declarationVersion,
            @Value("${platform.delivery.breaker-failure-threshold:3}") int failureThreshold,
            OpenTelemetry openTelemetry) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalStateException("platform.delivery.kafka.topic is required when transport=kafka");
        }
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        DeliveryCircuitBreaker breaker = new DeliveryCircuitBreaker(failureThreshold);
        return new KafkaDeliveryPublisher(outboxKafkaProducer, topic.trim(), declarationVersion, breaker, openTelemetry);
    }
}
