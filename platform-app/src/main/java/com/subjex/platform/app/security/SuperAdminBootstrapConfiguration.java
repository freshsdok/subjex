package com.subjex.platform.app.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SuperAdminBootstrapConfiguration — 破窗超管开通装配：仅当显式打开开关时写入超管并退出进程。
 * <p>
 * OFF by default. Enable with {@code --platform.operator.super-admin-bootstrap=true}.
 * Login and password come from {@code PLATFORM_SUPER_ADMIN_LOGIN} / {@code PLATFORM_SUPER_ADMIN_PASSWORD}
 * (properties {@code platform.operator.super-admin-login} / {@code platform.operator.super-admin-password}).
 * After a successful upsert the process exits. Never enable this by default in compose/k8s.
 * Isolated from ordinary {@link OperatorBootstrapConfiguration}.
 * 默认关闭。用 {@code --platform.operator.super-admin-bootstrap=true} 显式打开。成功写入后进程退出。
 * compose/k8s 默认不要打开。与普通开通隔离。
 */
@Configuration
@ConditionalOnProperty(name = "platform.operator.super-admin-bootstrap", havingValue = "true")
public class SuperAdminBootstrapConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SuperAdminBootstrapConfiguration.class);

    @Bean
    SuperAdminBootstrap superAdminBootstrap(
            JdbcTemplate jdbc, TransactionTemplate transaction, PasswordEncoder passwordEncoder) {
        return new SuperAdminBootstrap(jdbc, transaction, passwordEncoder);
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    ApplicationRunner superAdminBootstrapRunner(
            SuperAdminBootstrap bootstrap,
            ConfigurableApplicationContext context,
            @Value("${platform.operator.super-admin-login:}") String login,
            @Value("${platform.operator.super-admin-password:}") String password) {
        return args -> {
            LOG.warn(
                    "BOOTSTRAP SUPER-ADMIN: platform.operator.super-admin-bootstrap=true — "
                            + "provisioning break-glass {} then exiting. NOT for routine production starts. "
                            + "破窗开通：已打开 super-admin-bootstrap，写入超管后退出；不要作为日常生产启动。",
                    PlatformRoles.SUPER_ADMIN);
            int exitCode;
            try {
                bootstrap.upsert(login, password);
                LOG.warn(
                        "BOOTSTRAP SUPER-ADMIN complete for login '{}'. "
                                + "Disable platform.operator.super-admin-bootstrap and start normally. "
                                + "破窗开通完成（登录名 '{}'）。请关闭开关后正常启动。",
                        login,
                        login);
                exitCode = 0;
            } catch (RuntimeException ex) {
                LOG.error(
                        "BOOTSTRAP SUPER-ADMIN FAILED: {}. Fix login/password and retry. "
                                + "破窗开通失败，请检查登录名/口令后重试。",
                        ex.getMessage());
                exitCode = 1;
            }
            final int code = exitCode;
            System.exit(SpringApplication.exit(context, () -> code));
        };
    }
}
