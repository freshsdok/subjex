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
 * OperatorBootstrapConfiguration — 操作员开通装配：仅当显式打开开关时写入一位操作员并退出进程。
 * <p>
 * OFF by default. Enable with {@code --platform.operator.bootstrap=true} (or the matching property).
 * Login and password come from {@code PLATFORM_OPERATOR_LOGIN} / {@code PLATFORM_OPERATOR_PASSWORD}
 * (properties {@code platform.operator.login} / {@code platform.operator.password}); optional role from
 * {@code PLATFORM_OPERATOR_ROLE} (default {@code platform-operator}). After a successful upsert the process exits
 * so a normal production start never keeps this flag on. Never enable this by default in compose/k8s.
 * 默认关闭。用 {@code --platform.operator.bootstrap=true} 显式打开。登录名与口令来自环境变量；成功写入后进程退出，
 * 避免生产常开此开关。compose/k8s 默认不要打开。
 */
@Configuration
@ConditionalOnProperty(name = "platform.operator.bootstrap", havingValue = "true")
public class OperatorBootstrapConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(OperatorBootstrapConfiguration.class);

    @Bean
    OperatorBootstrap operatorBootstrap(
            JdbcTemplate jdbc, TransactionTemplate transaction, PasswordEncoder passwordEncoder) {
        return new OperatorBootstrap(jdbc, transaction, passwordEncoder);
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    ApplicationRunner operatorBootstrapRunner(
            OperatorBootstrap bootstrap,
            ConfigurableApplicationContext context,
            @Value("${platform.operator.login:}") String login,
            @Value("${platform.operator.password:}") String password,
            @Value("${platform.operator.role:platform-operator}") String role) {
        return args -> {
            LOG.warn(
                    "BOOTSTRAP: platform.operator.bootstrap=true — provisioning one operator then exiting. "
                            + "NOT for routine production starts. "
                            + "开通：已打开 platform.operator.bootstrap，写入一位操作员后退出；不要作为日常生产启动。");
            int exitCode;
            try {
                bootstrap.upsert(login, password, role);
                LOG.warn(
                        "BOOTSTRAP complete for login '{}'. Disable platform.operator.bootstrap and start normally. "
                                + "开通完成（登录名 '{}'）。请关闭开关后正常启动。",
                        login,
                        login);
                exitCode = 0;
            } catch (RuntimeException ex) {
                LOG.error(
                        "BOOTSTRAP FAILED: {}. Fix login/password/role and retry. 开通失败，请检查登录名/口令/角色后重试。",
                        ex.getMessage());
                exitCode = 1;
            }
            final int code = exitCode;
            System.exit(SpringApplication.exit(context, () -> code));
        };
    }
}
