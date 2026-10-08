package com.subjex.platform.app.declaration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcDeclarationStoreTest — 声明草稿：修订递增、latest、listLatest、租户隔离。
 */
class JdbcDeclarationStoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void savesRevisionsLatestAndIsolatesTenants(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);

        DeclarationRevision r1 = store.saveDraft(
                "acme", DeclarationKind.ENTITY, "demo-ticket", "entityKey: demo-ticket\nversion: 1\n", "sub-a");
        assertEquals(1, r1.revision());
        assertEquals(JdbcDeclarationStore.DRAFT_STATE, r1.draftState());

        DeclarationRevision r2 = store.saveDraft(
                "acme", DeclarationKind.ENTITY, "demo-ticket", "entityKey: demo-ticket\nversion: 2\n", "sub-b");
        assertEquals(2, r2.revision());
        assertEquals("entityKey: demo-ticket\nversion: 2\n", r2.yamlBody());

        assertEquals(2, store.latest("acme", DeclarationKind.ENTITY, "demo-ticket").orElseThrow().revision());
        assertEquals("sub-b", store.latest("acme", DeclarationKind.ENTITY, "demo-ticket").orElseThrow().updatedBySubjectId());

        store.saveDraft("acme", DeclarationKind.FORM, "demo-ticket-edit", "formKey: demo-ticket-edit\n", "sub-a");
        store.saveDraft("other", DeclarationKind.ENTITY, "demo-ticket", "entityKey: other\n", "sub-x");

        List<DeclarationRevision> entityLatest = store.listLatest("acme", DeclarationKind.ENTITY);
        assertEquals(1, entityLatest.size());
        assertEquals("demo-ticket", entityLatest.get(0).declarationKey());
        assertEquals(2, entityLatest.get(0).revision());

        List<DeclarationRevision> formLatest = store.listLatest("acme", DeclarationKind.FORM);
        assertEquals(1, formLatest.size());
        assertEquals("demo-ticket-edit", formLatest.get(0).declarationKey());

        List<DeclarationRevision> history = store.listRevisions("acme", DeclarationKind.ENTITY, "demo-ticket");
        assertEquals(2, history.size());
        assertEquals(2, history.get(0).revision());
        assertEquals(1, history.get(1).revision());

        assertTrue(store.latest("other", DeclarationKind.ENTITY, "demo-ticket").isPresent());
        assertEquals(1, store.latest("other", DeclarationKind.ENTITY, "demo-ticket").orElseThrow().revision());
        assertTrue(store.listLatest("other", DeclarationKind.ENTITY).stream()
                .noneMatch(r -> "acme".equals(r.tenantId())));
        assertTrue(store.latest("acme", DeclarationKind.FLOW, "missing").isEmpty());

        store.markPromoted("acme", DeclarationKind.ENTITY, "demo-ticket", 1);
        store.recordPromote("acme", DeclarationKind.ENTITY, "demo-ticket", 1, "sha-one", "sub-a");
        store.markPromoted("acme", DeclarationKind.ENTITY, "demo-ticket", 2);
        store.recordPromote("acme", DeclarationKind.ENTITY, "demo-ticket", 2, "sha-two", "sub-b");
        List<DeclarationPromote> promotes = store.listPromotes("acme", DeclarationKind.ENTITY, "demo-ticket");
        assertEquals(2, promotes.size());
        assertEquals(2, promotes.get(0).revision());
        assertEquals("sha-two", promotes.get(0).gitCommitSha());
        assertEquals(1, promotes.get(1).revision());
    }
}
