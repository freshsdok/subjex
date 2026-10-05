package com.subjex.platform.app.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * LocalOperatorConfiguration — 本地操作员装配：只在 profile {@code local} 下写入本地操作员。
 * <p>
 * LOCAL ONLY. Without {@code SPRING_PROFILES_ACTIVE=local} nothing here runs, and operators must be provisioned
 * as rows (see docs/operator-permissions.md). Flyway has already migrated when this runner starts.
 * 只用于本地。没有 {@code SPRING_PROFILES_ACTIVE=local} 时这里什么都不执行，操作员要以表行的方式开通
 * （见 docs/operator-permissions.md）。这个启动步骤开始时 Flyway 已经迁移完毕。
 */
@Configuration
@Profile("local")
public class LocalOperatorConfiguration {

    @Bean
    LocalOperatorSeeder localOperatorSeeder(
            JdbcTemplate jdbc, TransactionTemplate transaction, PasswordEncoder passwordEncoder) {
        return new LocalOperatorSeeder(jdbc, transaction, passwordEncoder);
    }

    @Bean
    ApplicationRunner localOperatorSeed(
            LocalOperatorSeeder seeder,
            @Value("${platform.local-operator.name}") String loginName,
            @Value("${platform.local-operator.password}") String password) {
        return args -> seeder.seed(loginName, password);
    }
}
