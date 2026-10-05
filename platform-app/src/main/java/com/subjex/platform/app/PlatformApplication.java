package com.subjex.platform.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PlatformApplication — 平台应用：空宿主进程。
 * <p>
 * Starts on either MySQL or PostgreSQL. Security is on. Health probes are the liveness and readiness endpoints.
 * Scheduling runs the outbox relay for PENDING rows.
 * 在 MySQL 或 PostgreSQL 上启动。安全默认开启。健康探针是存活与就绪端点。调度负责 PENDING 出箱重投。
 */
@SpringBootApplication
@EnableScheduling
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
