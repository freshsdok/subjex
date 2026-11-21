package com.subjex.platform.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ProductionProfileGuardTest — purpose/gate WP1-1: prod/production cannot combine with {@code local};
 * production start does not seed the laptop operator; the {@code local} laptop path still works;
 * non-dev manifests must not carry {@code change-me} / default {@code platform-operator} credentials.
 * <p>
 * 目的/门禁 WP1-1：prod/production 不可与 {@code local} 组合；生产启动不种入本机操作员；
 * {@code local} 本机路径仍可用；非开发清单不得带 {@code change-me} / 默认 {@code platform-operator} 口令。
 */
class ProductionProfileGuardTest {

    private static final ApplicationArguments NO_ARGS = new DefaultApplicationArguments();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProductionProfileGuardConfiguration.class, LocalOperatorConfiguration.class, SeedTestConfiguration.class)
            .withPropertyValues(
                    "platform.local-operator.name=platform-operator",
                    "platform.local-operator.password=change-me");

    /** ApplicationContextRunner does not honor {@code spring.profiles.active} as a Boot app would. */
    private ApplicationContextRunner runnerWithProfiles(String... profiles) {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles));
    }

    @Test
    void localPlusProdIsRejected() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ProductionProfileGuard.assertCompatible(new String[] {"local", "prod"}));
        assertTrue(ex.getMessage().contains("cannot be combined"));
        assertThrows(
                IllegalStateException.class,
                () -> ProductionProfileGuard.assertCompatible(new String[] {"production", "local"}));
    }

    @Test
    void localAloneOrProdAloneOrNoProfileIsAllowed() {
        ProductionProfileGuard.assertCompatible(new String[] {"local"});
        ProductionProfileGuard.assertCompatible(new String[] {"prod"});
        ProductionProfileGuard.assertCompatible(new String[] {"production"});
        ProductionProfileGuard.assertCompatible(new String[] {});
        ProductionProfileGuard.assertCompatible(null);
        assertTrue(ProductionProfileGuard.isLocalProfile(new String[] {"local"}));
        assertTrue(ProductionProfileGuard.isProductionProfile(new String[] {"prod"}));
        assertTrue(ProductionProfileGuard.isProductionProfile(new String[] {"production"}));
        assertFalse(ProductionProfileGuard.isProductionProfile(new String[] {"local"}));
    }

    @Test
    void localPlusProdContextFailsFastAndDoesNotSeed() {
        runnerWithProfiles("local", "prod").run(ProductionProfileGuardTest::assertStartupRefusedCombinedProfiles);
        runnerWithProfiles("local", "production").run(ProductionProfileGuardTest::assertStartupRefusedCombinedProfiles);
    }

    /**
     * Guard throws {@link IllegalStateException}; the context usually wraps it in a bean-creation failure.
     * 门禁抛出 {@link IllegalStateException}；容器通常再包一层 bean 创建失败。
     */
    private static void assertStartupRefusedCombinedProfiles(
            AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        assertTrue(failure != null, "expected startup failure when local is combined with production");
        Throwable current = failure;
        boolean found = false;
        while (current != null) {
            if (current instanceof IllegalStateException
                    && current.getMessage() != null
                    && current.getMessage().contains("cannot be combined")) {
                found = true;
                break;
            }
            current = current.getCause();
        }
        assertTrue(found, () -> "expected IllegalStateException about combined profiles, got: " + failure);
    }

    @Test
    void prodStartDoesNotSeedLocalOperators() {
        runnerWithProfiles("prod").run(context -> {
            assertFalse(context.getStartupFailure() != null, () -> String.valueOf(context.getStartupFailure()));
            assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(LocalOperatorSeeder.class));
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM account WHERE account_id = ?",
                            Integer.class,
                            LocalOperatorSeeder.ACCOUNT_ID));
            assertEquals(
                    0,
                    jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE login_name = 'platform-operator'", Integer.class));
        });
        runnerWithProfiles("production").run(context -> {
            assertFalse(context.getStartupFailure() != null, () -> String.valueOf(context.getStartupFailure()));
            assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(LocalOperatorSeeder.class));
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM account WHERE account_id = ?",
                            Integer.class,
                            LocalOperatorSeeder.ACCOUNT_ID));
        });
    }

    @Test
    void localLaptopPathStillSeedsTheDefaultOperator() {
        runnerWithProfiles("local").run(context -> {
            assertFalse(context.getStartupFailure() != null, () -> String.valueOf(context.getStartupFailure()));
            ApplicationRunner seed = context.getBean(ApplicationRunner.class);
            seed.run(NO_ARGS);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM account WHERE account_id = ? AND login_name = ?",
                            Integer.class,
                            LocalOperatorSeeder.ACCOUNT_ID,
                            "platform-operator"));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM subject_role WHERE subject_id = ? AND role_name = ?",
                            Integer.class,
                            LocalOperatorSeeder.SUBJECT_ID,
                            LocalOperatorSeeder.ROLE_NAME));
        });
    }

    @Test
    void nonDevManifestsDoNotContainLocalSeedPasswords() throws IOException {
        Path repo = repositoryRoot();
        Path k8s = repo.resolve("deploy/k8s");
        assertTrue(Files.isDirectory(k8s), "deploy/k8s must exist at " + k8s);
        try (Stream<Path> files = Files.walk(k8s)) {
            files.filter(Files::isRegularFile).forEach(ProductionProfileGuardTest::assertNoLocalSeedPassword);
        }
        Path helm = repo.resolve("deploy/helm");
        if (Files.isDirectory(helm)) {
            try (Stream<Path> files = Files.walk(helm)) {
                files.filter(Files::isRegularFile).forEach(ProductionProfileGuardTest::assertNoLocalSeedPassword);
            }
        }
    }

    private static void assertNoLocalSeedPassword(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".md")) {
            // Runbooks may mention the laptop seed as a warning; manifests must not carry the values.
            // 运行手册可以警告本机种子；清单本身不得带上这些值。
            return;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read " + file, ex);
        }
        String lower = text.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("change-me") || lower.contains("changeme"), () -> "placeholder password in " + file);
        assertFalse(
                lower.contains("platform-operator") && lower.contains("password"),
                () -> "default platform-operator credentials in " + file);
    }

    private static Path repositoryRoot() {
        Path start = Path.of("").toAbsolutePath().normalize();
        Path current = start;
        for (int i = 0; i < 6; i++) {
            if (Files.isDirectory(current.resolve("deploy/k8s")) && Files.isRegularFile(current.resolve("SECURITY.md"))) {
                return current;
            }
            Path parent = current.getParent();
            if (parent == null) {
                break;
            }
            current = parent;
        }
        throw new IllegalStateException("cannot locate repository root from " + start);
    }

    /**
     * In-memory Flyway schema so the seeder can run without Docker.
     * 内存 Flyway 库，让种子在无 Docker 时也能执行。
     */
    @Configuration
    static class SeedTestConfiguration {

        @Bean
        DataSource dataSource() {
            return H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        TransactionTemplate transactionTemplate(DataSource dataSource) {
            return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }
    }
}
