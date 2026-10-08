package com.subjex.platform.app.declaration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DeclarationPromoteServiceTest — 内部 git 晋升：写文件、commit sha、翻 PROMOTED、拒路径穿越。
 */
class DeclarationPromoteServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T14:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempGit;

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void promoteWritesFileCommitAndMarksPromoted(H2PlatformTables.Mode mode) throws Exception {
        DataSource source = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        DeclarationPromoteService service = newService(store, source);

        store.saveDraft("acme", DeclarationKind.ENTITY, "demo-ticket", "entityKey: demo-ticket\nversion: 1\n", "sub-a");

        DeclarationPromoteService.DeclarationPromoteResult first =
                service.promoteLatest("acme", DeclarationKind.ENTITY, "demo-ticket", "sub-a");
        assertEquals(1, first.revision());
        assertFalse(first.gitCommitSha().isBlank());
        assertTrue(first.gitCommitSha().matches("[0-9a-f]+"));
        assertTrue(first.gitCommitSha().length() >= 7);

        Path yaml = tempGit.resolve("acme/entity/demo-ticket.yaml");
        assertTrue(Files.isRegularFile(yaml));
        assertEquals("entityKey: demo-ticket\nversion: 1\n", Files.readString(yaml, StandardCharsets.UTF_8));
        assertEquals(
                JdbcDeclarationStore.PROMOTED_STATE,
                store.findRevision("acme", DeclarationKind.ENTITY, "demo-ticket", 1).orElseThrow().draftState());
        assertEquals(
                first.gitCommitSha(),
                jdbc.queryForObject(
                        """
                        SELECT git_commit_sha FROM declaration_promote
                        WHERE tenant_id = 'acme' AND declaration_kind = 'entity'
                          AND declaration_key = 'demo-ticket' AND revision = 1
                        """,
                        String.class));

        // latest still returns newest revision regardless of PROMOTED
        assertEquals(1, store.latest("acme", DeclarationKind.ENTITY, "demo-ticket").orElseThrow().revision());

        store.saveDraft("acme", DeclarationKind.ENTITY, "demo-ticket", "entityKey: demo-ticket\nversion: 2\n", "sub-b");
        assertEquals(2, store.latest("acme", DeclarationKind.ENTITY, "demo-ticket").orElseThrow().revision());
        assertEquals(
                JdbcDeclarationStore.DRAFT_STATE,
                store.latest("acme", DeclarationKind.ENTITY, "demo-ticket").orElseThrow().draftState());

        DeclarationPromoteService.DeclarationPromoteResult second =
                service.promoteLatest("acme", DeclarationKind.ENTITY, "demo-ticket", "sub-b");
        assertEquals(2, second.revision());
        assertFalse(second.gitCommitSha().isBlank());
        assertNotEquals(first.gitCommitSha(), second.gitCommitSha());
        assertEquals("entityKey: demo-ticket\nversion: 2\n", Files.readString(yaml, StandardCharsets.UTF_8));
        assertEquals(
                JdbcDeclarationStore.PROMOTED_STATE,
                store.findRevision("acme", DeclarationKind.ENTITY, "demo-ticket", 2).orElseThrow().draftState());
        // prior promoted revision stays PROMOTED; latest is still newest
        assertEquals(
                JdbcDeclarationStore.PROMOTED_STATE,
                store.findRevision("acme", DeclarationKind.ENTITY, "demo-ticket", 1).orElseThrow().draftState());
        assertEquals(2, store.latest("acme", DeclarationKind.ENTITY, "demo-ticket").orElseThrow().revision());
    }

    @Test
    void pathTraversalRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> InternalDeclarationGit.sanitizePathSegment("../evil", "tenantId"));
        assertThrows(
                IllegalArgumentException.class,
                () -> InternalDeclarationGit.sanitizePathSegment("a/b", "declarationKey"));
        assertThrows(
                IllegalArgumentException.class,
                () -> InternalDeclarationGit.sanitizePathSegment("a\\b", "declarationKey"));
        assertThrows(
                IllegalArgumentException.class, () -> InternalDeclarationGit.sanitizePathSegment("..", "tenantId"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void promoteMissingRevisionFails(H2PlatformTables.Mode mode) {
        DataSource source = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        DeclarationPromoteService service = newService(store, source);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.promoteLatest("acme", DeclarationKind.ENTITY, "missing", "sub-a"));
    }


    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void rePromoteAlreadyPromotedFails(H2PlatformTables.Mode mode) {
        DataSource source = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        DeclarationPromoteService service = newService(store, source);
        store.saveDraft("acme", DeclarationKind.ENTITY, "demo-ticket", "entityKey: demo-ticket\nversion: 1\n", "sub-a");
        service.promoteLatest("acme", DeclarationKind.ENTITY, "demo-ticket", "sub-a");
        assertThrows(
                DeclarationAlreadyPromoted.class,
                () -> service.promoteRevision("acme", DeclarationKind.ENTITY, "demo-ticket", 1, "sub-a"));
    }

    private DeclarationPromoteService newService(JdbcDeclarationStore store, DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        return new DeclarationPromoteService(
                store,
                new JdbcDeclarationMigrationStore(jdbc, CLOCK),
                new InternalDeclarationGit(),
                tempGit,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void entityPromoteBlockedWhenMigrationPending(H2PlatformTables.Mode mode) {
        DataSource source = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        JdbcDeclarationMigrationStore migrations = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        DeclarationPromoteService service = new DeclarationPromoteService(
                store,
                migrations,
                new InternalDeclarationGit(),
                tempGit,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
        store.saveDraft("acme", DeclarationKind.ENTITY, "demo-ticket", "entityKey: demo-ticket\nversion: 1\n", "sub-a");
        migrations.enqueue(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                1,
                "ALTER TABLE demo_ticket ADD COLUMN x INT",
                "sub-a");
        assertThrows(
                DeclarationPromoteBlockedByMigration.class,
                () -> service.promoteLatest("acme", DeclarationKind.ENTITY, "demo-ticket", "sub-a"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void entityPromoteAllowedWhenMigrationApplied(H2PlatformTables.Mode mode) {
        DataSource source = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        JdbcDeclarationMigrationStore migrations = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        DeclarationPromoteService service = new DeclarationPromoteService(
                store,
                migrations,
                new InternalDeclarationGit(),
                tempGit,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
        store.saveDraft("acme", DeclarationKind.ENTITY, "demo-ticket", "entityKey: demo-ticket\nversion: 1\n", "sub-a");
        DeclarationMigration row = migrations.enqueue(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                1,
                "ALTER TABLE demo_ticket ADD COLUMN x INT",
                "sub-a");
        migrations.markReviewed(row.migrationId());
        migrations.markApplied(row.migrationId());
        DeclarationPromoteService.DeclarationPromoteResult result =
                service.promoteLatest("acme", DeclarationKind.ENTITY, "demo-ticket", "sub-a");
        assertEquals(1, result.revision());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void formPromoteIgnoresMigrationQueue(H2PlatformTables.Mode mode) {
        DataSource source = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        JdbcDeclarationMigrationStore migrations = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        DeclarationPromoteService service = new DeclarationPromoteService(
                store,
                migrations,
                new InternalDeclarationGit(),
                tempGit,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
        store.saveDraft("acme", DeclarationKind.FORM, "demo-form", "formKey: demo-form\nversion: 1\n", "sub-a");
        migrations.enqueue(
                "acme",
                DeclarationKind.FORM,
                "demo-form",
                1,
                "ALTER TABLE anything ADD COLUMN x INT",
                "sub-a");
        DeclarationPromoteService.DeclarationPromoteResult result =
                service.promoteLatest("acme", DeclarationKind.FORM, "demo-form", "sub-a");
        assertEquals(1, result.revision());
    }
}
