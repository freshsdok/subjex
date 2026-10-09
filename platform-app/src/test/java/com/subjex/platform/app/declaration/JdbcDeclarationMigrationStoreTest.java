package com.subjex.platform.app.declaration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;


/**
 * JdbcDeclarationMigrationStoreTest — purpose: migration queue JDBC state transitions.
 * Gates: enqueue PENDING; review/apply/fail/cancel transitions; dual H2 MODE.
 * <p>
 * 目的：迁移队列 JDBC 状态流转。门禁：入队 PENDING；审阅/执行/失败/取消；双 H2 模式。
 */
class JdbcDeclarationMigrationStoreTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void enqueuesListsReviewsAndMarksResults(H2PlatformTables.Mode mode) {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-08T14:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcDeclarationMigrationStore store = new JdbcDeclarationMigrationStore(jdbc, clock);

        DeclarationMigration first = store.enqueue(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                2,
                "ALTER TABLE demo_ticket ADD COLUMN priority VARCHAR(32)",
                "sub-a");
        assertEquals(JdbcDeclarationMigrationStore.PENDING, first.status());
        assertEquals(2, first.declarationRevision());
        assertNull(first.appliedAt());
        assertNull(first.errorMessage());

        now.set(Instant.parse("2026-10-08T14:00:01Z"));
        DeclarationMigration second = store.enqueue(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                3,
                "ALTER TABLE demo_ticket ADD COLUMN due_date VARCHAR(10)",
                "sub-b");

        List<DeclarationMigration> listed = store.list("acme", DeclarationKind.ENTITY, "demo-ticket");
        assertEquals(2, listed.size());
        assertEquals(second.migrationId(), listed.get(0).migrationId());
        assertEquals(first.migrationId(), listed.get(1).migrationId());

        store.enqueue(
                "other", DeclarationKind.ENTITY, "demo-ticket", 1, "ALTER TABLE demo_ticket ADD COLUMN x INT", "sub-x");
        assertEquals(2, store.list("acme", DeclarationKind.ENTITY, "demo-ticket").size());
        assertEquals(1, store.list("other", DeclarationKind.ENTITY, "demo-ticket").size());

        DeclarationMigration reviewed = store.markReviewed(first.migrationId());
        assertEquals(JdbcDeclarationMigrationStore.REVIEWED, reviewed.status());
        assertThrows(IllegalArgumentException.class, () -> store.markReviewed(first.migrationId()));

        DeclarationMigration applied = store.markApplied(first.migrationId());
        assertEquals(JdbcDeclarationMigrationStore.APPLIED, applied.status());
        assertEquals(Instant.parse("2026-10-08T14:00:01Z"), applied.appliedAt());

        DeclarationMigration pendingFail = store.enqueue(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                4,
                "ALTER TABLE demo_ticket ADD COLUMN z INT",
                "sub-c");
        store.markReviewed(pendingFail.migrationId());
        DeclarationMigration failed = store.markFailed(pendingFail.migrationId(), "table missing");
        assertEquals(JdbcDeclarationMigrationStore.FAILED, failed.status());
        assertEquals("table missing", failed.errorMessage());
        assertNull(failed.appliedAt());

        DeclarationMigration cancelMe = store.enqueue(
                "acme",
                DeclarationKind.ENTITY,
                "service-note",
                1,
                "ALTER TABLE service_note ADD COLUMN tag VARCHAR(32)",
                "sub-a");
        DeclarationMigration cancelled = store.markCancelled(cancelMe.migrationId());
        assertEquals(JdbcDeclarationMigrationStore.CANCELLED, cancelled.status());

        assertTrue(store.findById(first.migrationId()).isPresent());
        assertTrue(store.findById("missing-id").isEmpty());
    }
}
