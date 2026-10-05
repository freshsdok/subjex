package com.subjex.sample.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ConsumerWiring — 消费者装配：出箱套接字监听，以及收下事件的记录器。
 */
@Configuration
public class ConsumerWiring {

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
