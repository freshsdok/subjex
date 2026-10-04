package com.subjex.platform.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Platform application entry — 平台应用入口。
 * <p>
 * Starts without a datasource; health is exposed via Actuator.
 */
@SpringBootApplication
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
