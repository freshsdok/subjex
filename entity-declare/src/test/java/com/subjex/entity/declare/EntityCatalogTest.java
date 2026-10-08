package com.subjex.entity.declare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * EntityCatalogTest — 实体目录测试：加载 classpath 样例；userRef/orgRef 解析；未知 kind 仍拒绝。
 */
class EntityCatalogTest {

    @Test
    void loadsBothCheckedInEntitiesFromClasspath() {
        EntityCatalog catalog = EntityCatalog.load(EntityCatalogTest.class.getClassLoader());

        assertEquals(2, catalog.all().size());
        assertTrue(catalog.find("service-note").isPresent());
        Optional<RenderedEntity> ticket = catalog.find("demo-ticket");
        assertTrue(ticket.isPresent());
        assertEquals("demo_ticket", ticket.get().tableName());
        assertEquals(5, ticket.get().fields().size());
        assertEquals(EntityFieldKind.USER_REF, ticket.get().fields().get(3).kind());
        assertEquals(EntityFieldKind.ORG_REF, ticket.get().fields().get(4).kind());
        assertEquals(64, ticket.get().fields().get(3).maxLength());
        assertEquals(64, ticket.get().fields().get(4).maxLength());
    }

    @Test
    void userRefAndOrgRefParseAndMapToVarchar() {
        String yaml = """
                entityKey: ref-sample
                tableName: ref_sample
                version: 1
                permission: page.read
                fields:
                  - name: itemId
                    kind: text
                    required: true
                    maxLength: 32
                  - name: owner
                    kind: userRef
                    required: false
                    maxLength: 64
                  - name: dept
                    kind: orgRef
                    required: false
                """;
        RenderedEntity entity = new EntityRenderer().render(yaml);
        assertEquals(EntityFieldKind.USER_REF, entity.fields().get(1).kind());
        assertEquals(EntityFieldKind.ORG_REF, entity.fields().get(2).kind());
        String sql = new EntityMigrationGenerator().sql(entity);
        assertTrue(sql.contains("owner VARCHAR(64)"));
        assertTrue(sql.contains("dept VARCHAR(64)"));
    }

    @Test
    void unknownKindIsStillRejected() {
        String yaml = """
                entityKey: bad-kind
                tableName: bad_kind
                version: 1
                permission: page.read
                fields:
                  - name: title
                    kind: token
                    required: true
                """;
        EntityDefinitionRejected ex =
                assertThrows(EntityDefinitionRejected.class, () -> new EntityRenderer().render(yaml));
        assertTrue(ex.getMessage().contains("userRef"));
    }

    @Test
    void duplicateEntityKeyIsRejected() {
        EntityRenderer renderer = new EntityRenderer();
        RenderedEntity first = renderer.render("""
                entityKey: dup-key
                tableName: dup_a
                version: 1
                permission: page.read
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 8
                """);
        RenderedEntity second = renderer.render("""
                entityKey: dup-key
                tableName: dup_b
                version: 1
                permission: page.read
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 8
                """);
        EntityDefinitionRejected ex =
                assertThrows(EntityDefinitionRejected.class, () -> EntityCatalog.of(List.of(first, second)));
        assertTrue(ex.getMessage().contains("duplicate entityKey"));
    }

    @Test
    void booleanEnumDateAndEntityRefParseAndMapToSql() {
        String yaml = """
                entityKey: kind-sample
                tableName: kind_sample
                version: 1
                permission: page.read
                fields:
                  - name: itemId
                    kind: text
                    required: true
                    maxLength: 32
                  - name: urgent
                    kind: boolean
                    required: true
                  - name: status
                    kind: enum
                    required: true
                    enumValues: [open, closed]
                    maxLength: 16
                  - name: dueDate
                    kind: date
                    required: false
                  - name: related
                    kind: entityRef
                    required: false
                    refEntityKey: demo-ticket
                """;
        RenderedEntity entity = new EntityRenderer().render(yaml);
        assertEquals(EntityFieldKind.BOOLEAN, entity.fields().get(1).kind());
        assertEquals(EntityFieldKind.ENUM, entity.fields().get(2).kind());
        assertEquals(List.of("open", "closed"), entity.fields().get(2).enumValues());
        assertEquals(EntityFieldKind.DATE, entity.fields().get(3).kind());
        assertEquals(EntityFieldKind.ENTITY_REF, entity.fields().get(4).kind());
        assertEquals("demo-ticket", entity.fields().get(4).refEntityKey());
        String sql = new EntityMigrationGenerator().sql(entity);
        assertTrue(sql.contains("urgent BOOLEAN NOT NULL"));
        assertTrue(sql.contains("status VARCHAR(16) NOT NULL"));
        assertTrue(sql.contains("due_date VARCHAR(10)"));
        assertTrue(sql.contains("related VARCHAR(64)"));
    }

    @Test
    void enumWithoutValuesIsRejected() {
        String yaml = """
                entityKey: bad-enum
                tableName: bad_enum
                version: 1
                permission: page.read
                fields:
                  - name: status
                    kind: enum
                    required: true
                """;
        assertThrows(EntityDefinitionRejected.class, () -> new EntityRenderer().render(yaml));
    }

    @Test
    void enumValuesOnNonEnumIsRejected() {
        String yaml = """
                entityKey: bad-enum-values
                tableName: bad_enum_values
                version: 1
                permission: page.read
                fields:
                  - name: title
                    kind: text
                    required: true
                    enumValues: [a]
                """;
        assertThrows(EntityDefinitionRejected.class, () -> new EntityRenderer().render(yaml));
    }

    @Test
    void maxLengthOnBooleanOrDateIsRejected() {
        String boolYaml = """
                entityKey: bad-bool
                tableName: bad_bool
                version: 1
                permission: page.read
                fields:
                  - name: flag
                    kind: boolean
                    required: true
                    maxLength: 1
                """;
        assertThrows(EntityDefinitionRejected.class, () -> new EntityRenderer().render(boolYaml));
        String dateYaml = """
                entityKey: bad-date
                tableName: bad_date
                version: 1
                permission: page.read
                fields:
                  - name: due
                    kind: date
                    required: false
                    maxLength: 10
                """;
        assertThrows(EntityDefinitionRejected.class, () -> new EntityRenderer().render(dateYaml));
    }

}
