package com.subjex.sample.consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ConsumerWiring — 消费者装配：收下事件的记录器。
 */
@Configuration
public class ConsumerWiring {

    @Bean
    TaskRecordedReceipts taskRecordedReceipts() {
        return new TaskRecordedReceipts();
    }
}
