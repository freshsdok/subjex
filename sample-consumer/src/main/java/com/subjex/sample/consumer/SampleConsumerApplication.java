package com.subjex.sample.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SampleConsumerApplication — 示例消费者应用：只订阅一条跨进程事件的第二个进程。
 * <p>
 * It does not host the platform tables. platform-app delivers {@code TaskRecorded} here.
 * 它不托管平台表。platform-app 把 {@code TaskRecorded} 送到这里。
 */
@SpringBootApplication
public class SampleConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleConsumerApplication.class, args);
    }
}
