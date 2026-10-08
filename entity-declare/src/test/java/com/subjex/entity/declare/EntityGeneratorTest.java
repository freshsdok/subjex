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

        // Draft header may note Flyway promotion; compare CREATE TABLE body only.
        // 草稿头可能注明已晋升 Flyway；只比对 CREATE TABLE 本体。
        assertEquals(
                createTableBody(migration.sql(entity)),
                createTableBody(readCheckedIn(
                        "entity-declare/src/main/resources/db/migration-draft/V1__service_note.sql")));
        assertEquals(
                crud.recordSource(entity, pkg),
                readCheckedIn("entity-declare/src/main/java/com/subjex/entity/generated/ServiceNote.java"));
        // Store/JDBC stubs were hand-adapted when wired into platform-app; match signatures + SQL.
        // Store/JDBC 桩在接入 platform-app 时已人工改编；比对签名与 SQL。
        String store = readCheckedIn(
                "entity-declare/src/main/java/com/subjex/entity/generated/ServiceNoteStore.java");
        assertTrue(store.contains("interface ServiceNoteStore"));
        assertTrue(store.contains("void save(ServiceNote row)"));
        assertTrue(store.contains("Optional<ServiceNote> findById(String noteId)"));
        String jdbc = readCheckedIn(
                "entity-declare/src/main/java/com/subjex/entity/generated/JdbcServiceNoteStore.java");
        assertTrue(jdbc.contains("class JdbcServiceNoteStore implements ServiceNoteStore"));
        assertTrue(jdbc.contains("INSERT INTO service_note (note_id, title, body, priority)"));
        assertTrue(jdbc.contains("WHERE note_id = ?"));
        assertTrue(jdbc.contains("FROM service_note LIMIT ?"));
    }

    private static String createTableBody(String sql) {
        int at = sql.indexOf("CREATE TABLE");
        if (at < 0) {
            throw new IllegalArgumentException("CREATE TABLE missing");
        }
        return sql.substring(at).replace("\r\n", "\n");
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

    @Test
    void migrationDraftMapsNewFieldKinds() {
        RenderedEntity entity = new EntityRenderer().render("""
                entityKey: kind-map
                tableName: kind_map
                version: 1
                permission: page.read
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 8
                  - name: urgent
                    kind: boolean
                    required: false
                  - name: status
                    kind: enum
                    required: true
                    enumValues: [a, b]
                  - name: dueDate
                    kind: date
                    required: false
                  - name: link
                    kind: entityRef
                    required: false
                """);
        String sql = new EntityMigrationGenerator().sql(entity);
        assertTrue(sql.contains("urgent BOOLEAN"));
        assertTrue(sql.contains("status VARCHAR(64) NOT NULL"));
        assertTrue(sql.contains("due_date VARCHAR(10)"));
        assertTrue(sql.contains("link VARCHAR(64)"));
        EntityCrudStubGenerator crud = new EntityCrudStubGenerator();
        String record = crud.recordSource(entity, "com.subjex.entity.generated");
        assertTrue(record.contains("Boolean urgent"));
        assertTrue(record.contains("String status"));
        assertTrue(record.contains("String dueDate"));
        assertTrue(record.contains("String link"));
    }

}
