package com.subjex.platform.app.declaration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


/**
 * DeclarationMigrationApplyServiceTest — purpose: REVIEWED entity DDL apply whitelist.
 * Gates: non-REVIEWED refuse; unsafe SQL refuse; success marks APPLIED; failure marks FAILED (fail-closed).
 * <p>
 * 目的：REVIEWED 实体 DDL 执行白名单。门禁：非 REVIEWED 拒绝；不安全 SQL 拒绝；成功 APPLIED；失败 FAILED。
 */
class DeclarationMigrationApplyServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T15:00:00Z"), ZoneOffset.UTC);

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void appliesCreateAndAlterOnH2(H2PlatformTables.Mode mode) {
        DataSource source = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcDeclarationMigrationStore store = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        DeclarationMigrationApplyService apply = newApply(store, source);

        DeclarationMigration createJob = store.enqueue(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                1,
                "CREATE TABLE mq2_demo_ticket (id VARCHAR(64) NOT NULL, PRIMARY KEY (id))",
                "sub-a");
        store.markReviewed(createJob.migrationId());
        DeclarationMigration created = apply.apply("acme", DeclarationKind.ENTITY, "demo-ticket", createJob.migrationId());
        assertEquals(JdbcDeclarationMigrationStore.APPLIED, created.status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mq2_demo_ticket", Integer.class));

        DeclarationMigration alterJob = store.enqueue(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                2,
                "ALTER TABLE mq2_demo_ticket ADD COLUMN title VARCHAR(128)",
                "sub-a");
        store.markReviewed(alterJob.migrationId());
        DeclarationMigration altered = apply.apply("acme", DeclarationKind.ENTITY, "demo-ticket", alterJob.migrationId());
        assertEquals(JdbcDeclarationMigrationStore.APPLIED, altered.status());
        jdbc.update("INSERT INTO mq2_demo_ticket (id, title) VALUES ('t1', 'hello')");
        assertEquals("hello", jdbc.queryForObject("SELECT title FROM mq2_demo_ticket WHERE id = 't1'", String.class));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void rejectsDropSql(H2PlatformTables.Mode mode) {
        DataSource source = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcDeclarationMigrationStore store = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        DeclarationMigrationApplyService apply = newApply(store, source);

        DeclarationMigration dropJob = store.enqueue(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                1,
                "DROP TABLE mq2_should_not_run",
                "sub-a");
        store.markReviewed(dropJob.migrationId());
        assertThrows(
                IllegalArgumentException.class,
                () -> apply.apply("acme", DeclarationKind.ENTITY, "demo-ticket", dropJob.migrationId()));
        assertEquals(
                JdbcDeclarationMigrationStore.REVIEWED,
                store.findById(dropJob.migrationId()).orElseThrow().status());

        DeclarationMigration alterDrop = store.enqueue(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                2,
                "ALTER TABLE mq2_demo_ticket DROP COLUMN title",
                "sub-a");
        store.markReviewed(alterDrop.migrationId());
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> apply.apply("acme", DeclarationKind.ENTITY, "demo-ticket", alterDrop.migrationId()));
        assertTrue(ex.getMessage().toLowerCase().contains("drop"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void rejectsPendingNotReviewed(H2PlatformTables.Mode mode) {
        DataSource source = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcDeclarationMigrationStore store = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        DeclarationMigrationApplyService apply = newApply(store, source);

        DeclarationMigration pending = store.enqueue(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                1,
                "CREATE TABLE mq2_pending_only (id INT)",
                "sub-a");
        assertThrows(
                DeclarationMigrationNotReady.class,
                () -> apply.apply("acme", DeclarationKind.ENTITY, "demo-ticket", pending.migrationId()));
        assertEquals(
                JdbcDeclarationMigrationStore.PENDING,
                store.findById(pending.migrationId()).orElseThrow().status());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void rejectsFormKind(H2PlatformTables.Mode mode) {
        DataSource source = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcDeclarationMigrationStore store = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        DeclarationMigrationApplyService apply = newApply(store, source);
        DeclarationMigration row = store.enqueue(
                "acme",
                DeclarationKind.FORM,
                "demo-form",
                1,
                "CREATE TABLE mq2_form_no (id INT)",
                "sub-a");
        store.markReviewed(row.migrationId());
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> apply.apply("acme", DeclarationKind.FORM, "demo-form", row.migrationId()));
        assertTrue(ex.getMessage().contains("entity"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void marksFailedWhenDdlErrors(H2PlatformTables.Mode mode) {
        DataSource source = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcDeclarationMigrationStore store = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        DeclarationMigrationApplyService apply = newApply(store, source);
        DeclarationMigration row = store.enqueue(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                1,
                "ALTER TABLE mq2_missing_table ADD COLUMN x INT",
                "sub-a");
        store.markReviewed(row.migrationId());
        assertThrows(
                DeclarationMigrationApplyFailed.class,
                () -> apply.apply("acme", DeclarationKind.ENTITY, "demo-ticket", row.migrationId()));
        DeclarationMigration failed = store.findById(row.migrationId()).orElseThrow();
        assertEquals(JdbcDeclarationMigrationStore.FAILED, failed.status());
        assertTrue(failed.errorMessage() != null && !failed.errorMessage().isBlank());
    }

    @Test
    void validateSqlRules() {
        assertEquals(
                "CREATE TABLE t (id INT)",
                DeclarationMigrationApplyService.validateSqlForApply("CREATE TABLE t (id INT);"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DeclarationMigrationApplyService.validateSqlForApply(
                        "CREATE TABLE t (id INT); DROP TABLE t"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DeclarationMigrationApplyService.validateSqlForApply("DELETE FROM t"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DeclarationMigrationApplyService.validateSqlForApply("TRUNCATE TABLE t"));
    }

    private static DeclarationMigrationApplyService newApply(
            JdbcDeclarationMigrationStore store, DataSource source) {
        return new DeclarationMigrationApplyService(
                store,
                new JdbcTemplate(source),
                new TransactionTemplate(new DataSourceTransactionManager(source)));
    }
}
