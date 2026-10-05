package com.subjex.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * EntryGatewayApplication — 入口网关应用：把 HTTP 转发到 platform-app，并做粗粒度限流。
 * <p>
 * It does not host platform tables and does not decide operator permissions. Auth headers pass through.
 * 它不托管平台表，也不决定操作员权限。认证头原样转发。
 */
@SpringBootApplication
public class EntryGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(EntryGatewayApplication.class, args);
    }
}
