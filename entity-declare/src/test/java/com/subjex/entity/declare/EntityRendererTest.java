package com.subjex.entity.declare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;



/**
 * EntityRendererTest — purpose: render/validate declarative entity YAML to RenderedEntity.
 * Gates: required fields; primary key; reject unknown kinds / blank keys (fail-closed).
 * <p>
 * 目的：声明式实体 YAML 渲染校验为 RenderedEntity。门禁：必填/主键；未知 kind/空白键失败关闭。
 */
class EntityRendererTest {

    private final EntityRenderer renderer = new EntityRenderer();

    @Test
    void rendersTheCheckedInEntityAsAValidatedFieldList() throws IOException {
        RenderedEntity entity = renderer.render(entityYaml());

        assertEquals("service-note", entity.entityKey());
        assertEquals("service_note", entity.tableName());
        assertEquals(1, entity.version());
        assertEquals("page.read", entity.permission());
        assertFalse(entity.tenantScoped());
        assertEquals("ServiceNote", entity.typeName());
        assertEquals(4, entity.fields().size());
        assertEquals(new EntityField("noteId", EntityFieldKind.TEXT, true, 64), entity.fields().get(0));
        assertEquals(new EntityField("title", EntityFieldKind.TEXT, true, 200), entity.fields().get(1));
        assertEquals(new EntityField("body", EntityFieldKind.TEXT, false, 4000), entity.fields().get(2));
        assertEquals(new EntityField("priority", EntityFieldKind.INTEGER, false, null), entity.fields().get(3));
        assertEquals("note_id", entity.fields().get(0).columnName());
    }

    @Test
    void missingVersionIsRejected() {
        String yaml = """
                entityKey: demo-item
                tableName: demo_item
                permission: page.read
                fields:
                  - name: itemId
                    kind: text
                    required: true
                    maxLength: 32
                """;
        assertThrows(EntityDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void versionBelowOneIsRejected() {
        String yaml = """
                entityKey: demo-item
                tableName: demo_item
                version: 0
                permission: page.read
                fields:
                  - name: itemId
                    kind: text
                    required: true
                    maxLength: 32
                """;
        assertThrows(EntityDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void missingPermissionIsRejected() {
        String yaml = """
                entityKey: service-note
                tableName: service_note
                version: 1
                fields:
                  - name: title
                    kind: text
                    required: true
                """;
        assertThrows(EntityDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void duplicateFieldNameIsRejected() {
        String yaml = """
                entityKey: service-note
                tableName: service_note
                version: 1
                permission: page.read
                fields:
                  - name: title
                    kind: text
                    required: true
                  - name: title
                    kind: text
                    required: true
                """;
        assertThrows(EntityDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void unknownKindIsRejected() {
        String yaml = """
                entityKey: service-note
                tableName: service_note
                permission: page.read
                fields:
                  - name: title
                    kind: token
                    required: true
                """;
        assertThrows(EntityDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void badTableNameIsRejected() {
        String yaml = """
                entityKey: service-note
                tableName: Service-Note
                version: 1
                permission: page.read
                fields:
                  - name: title
                    kind: text
                    required: true
                """;
        assertThrows(EntityDefinitionRejected.class, () -> renderer.render(yaml));
    }

    static String entityYaml() throws IOException {
        try (var in = EntityRendererTest.class.getResourceAsStream("/entities/service-note.entity.yaml")) {
            if (in == null) {
                throw new IOException("entity definition is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
