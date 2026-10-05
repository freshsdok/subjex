package com.subjex.entity.declare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * EntityGeneratorTest — 生成器测试：SQL / Java 草稿包含期望的列名与类型名。
 */
class EntityGeneratorTest {

    @Test
    void migrationDraftContainsExpectedColumnsAndTable() throws IOException {
        RenderedEntity entity = new EntityRenderer().render(EntityRendererTest.entityYaml());
        String sql = new EntityMigrationGenerator().sql(entity);

        assertTrue(sql.contains("CREATE TABLE service_note"));
        assertTrue(sql.contains("note_id VARCHAR(64) NOT NULL"));
        assertTrue(sql.contains("title VARCHAR(200) NOT NULL"));
        assertTrue(sql.contains("body VARCHAR(4000)"));
        assertTrue(sql.contains("priority INTEGER"));
        assertTrue(sql.contains("PRIMARY KEY (note_id)"));
        assertEquals("V1__service_note.sql", new EntityMigrationGenerator().fileName(entity));
    }

    @Test
    void crudStubsContainExpectedTypeAndSqlNames() throws IOException {
        RenderedEntity entity = new EntityRenderer().render(EntityRendererTest.entityYaml());
        EntityCrudStubGenerator crud = new EntityCrudStubGenerator();
        String pkg = "com.subjex.entity.generated";

        String record = crud.recordSource(entity, pkg);
        assertTrue(record.contains("public record ServiceNote("));
        assertTrue(record.contains("String noteId"));
        assertTrue(record.contains("String title"));
        assertTrue(record.contains("String body"));
        assertTrue(record.contains("Integer priority"));

        String store = crud.storeSource(entity, pkg);
        assertTrue(store.contains("interface ServiceNoteStore"));
        assertTrue(store.contains("void save(ServiceNote row)"));
        assertTrue(store.contains("Optional<ServiceNote> findById(String noteId)"));

        String jdbc = crud.jdbcSource(entity, pkg);
        assertTrue(jdbc.contains("class JdbcServiceNoteStore implements ServiceNoteStore"));
        assertTrue(jdbc.contains("INSERT INTO service_note (note_id, title, body, priority)"));
        assertTrue(jdbc.contains("WHERE note_id = ?"));
        assertTrue(jdbc.contains("FROM service_note LIMIT ?"));
    }

    @Test
    void checkedInDraftsMatchGenerators() throws IOException {
        RenderedEntity entity = new EntityRenderer().render(EntityRendererTest.entityYaml());
        EntityMigrationGenerator migration = new EntityMigrationGenerator();
        EntityCrudStubGenerator crud = new EntityCrudStubGenerator();
        String pkg = "com.subjex.entity.generated";

        assertEquals(
                migration.sql(entity),
                readCheckedIn("entity-declare/src/main/resources/db/migration-draft/V1__service_note.sql"));
        assertEquals(
                crud.recordSource(entity, pkg),
                readCheckedIn("entity-declare/src/main/java/com/subjex/entity/generated/ServiceNote.java"));
        assertEquals(
                crud.storeSource(entity, pkg),
                readCheckedIn("entity-declare/src/main/java/com/subjex/entity/generated/ServiceNoteStore.java"));
        assertEquals(
                crud.jdbcSource(entity, pkg),
                readCheckedIn("entity-declare/src/main/java/com/subjex/entity/generated/JdbcServiceNoteStore.java"));
    }

    private static String readCheckedIn(String relative) throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(relative + " was not found");
    }
}
