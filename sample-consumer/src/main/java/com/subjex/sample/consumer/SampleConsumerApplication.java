package com.subjex.sample.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample consumer application — 示例消费者应用。
 * <p>
 * Does not call platform-app yet. A dedicated subscriber type documents the
 * planned cross-process event subscription.
 * <br>
 * 尚不调用 platform-app。专用订阅类型用于说明后续跨进程事件订阅意图。
 */
@SpringBootApplication
public class SampleConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleConsumerApplication.class, args);
    }
}
