package com.subjex.form.render;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormRendererTest {

    private final FormRenderer renderer = new FormRenderer();

    @Test
    void rendersTheCheckedInFormAsAValidatedFieldList() throws IOException {
        RenderedForm form = renderer.render(formYaml());

        assertEquals("endpoint-publication", form.formKey());
        assertEquals(2, form.version());
        assertEquals("registry.write", form.permission());
        assertFalse(form.tenantScoped());
        assertEquals(DomainActionKey.REGISTRY_REGISTER, form.domainAction());
        assertEquals("EndpointPublication", form.recordName());
        assertEquals(3, form.fields().size());
        assertEquals(new FormField("serviceName", FieldKind.TEXT, true, null, null, 64, List.of()), form.fields().get(0));
        assertEquals(new FormField("host", FieldKind.TEXT, true, null, null, 253, List.of()), form.fields().get(1));
        assertEquals(new FormField("port", FieldKind.INTEGER, true, 1, 65535, null, List.of()), form.fields().get(2));
        assertEquals(2, form.effects().size());
        assertEquals(SideEffectKey.AUDIT_WRITE, form.effects().get(0).key());
        assertEquals(
                Map.of("actionName", "registry.register", "actionTargetField", "serviceName"),
                form.effects().get(0).params());
        assertEquals(SideEffectKey.TASK_ENQUEUE, form.effects().get(1).key());
    }

    @Test
    void missingPermissionIsRejected() {
        String yaml = """
                formKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                version: 1
                fields:
                  - name: host
                    kind: text
                    required: true
                """;
        assertThrows(FormDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void missingVersionIsRejected() {
        String yaml = """
                formKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                permission: registry.write
                fields:
                  - name: host
                    kind: text
                    required: true
                """;
        assertThrows(FormDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void blankOrBadPermissionIsRejected() {
        String blank = """
                formKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                version: 1
                permission: "  "
                fields:
                  - name: host
                    kind: text
                    required: true
                """;
        assertThrows(FormDefinitionRejected.class, () -> renderer.render(blank));

        String bad = """
                formKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                version: 1
                permission: RegistryWrite
                fields:
                  - name: host
                    kind: text
                    required: true
                """;
        assertThrows(FormDefinitionRejected.class, () -> renderer.render(bad));
    }

    @Test
    void duplicateFieldNameIsRejected() {
        String yaml = """
                formKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                version: 1
                permission: registry.write
                fields:
                  - name: host
                    kind: text
                    required: true
                  - name: host
                    kind: text
                    required: true
                """;

        assertThrows(FormDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void unknownKindIsRejected() {
        String yaml = """
                formKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                version: 1
                permission: registry.write
                fields:
                  - name: host
                    kind: token
                    required: true
                """;

        assertThrows(FormDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void unknownEffectKeyIsRejected() {
        String yaml = """
                formKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                version: 1
                permission: registry.write
                domainAction: registry.register
                fields:
                  - name: serviceName
                    kind: text
                    required: true
                  - name: host
                    kind: text
                    required: true
                  - name: port
                    kind: integer
                    required: true
                effects:
                  - key: webhook.call
                    params:
                      url: https://example.invalid
                """;
        FormDefinitionRejected rejected =
                assertThrows(FormDefinitionRejected.class, () -> renderer.render(yaml));
        assertTrue(rejected.getMessage().contains("unknown effect key"));
    }

    @Test
    void effectMissingRequiredParamIsRejected() {
        String yaml = """
                formKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                version: 1
                permission: registry.write
                domainAction: registry.register
                fields:
                  - name: serviceName
                    kind: text
                    required: true
                  - name: host
                    kind: text
                    required: true
                  - name: port
                    kind: integer
                    required: true
                effects:
                  - key: audit.write
                    params: {}
                """;
        assertThrows(FormDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void missingDomainActionIsRejected() {
        String yaml = """
                formKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                version: 1
                permission: registry.write
                fields:
                  - name: serviceName
                    kind: text
                    required: true
                  - name: host
                    kind: text
                    required: true
                  - name: port
                    kind: integer
                    required: true
                """;
        FormDefinitionRejected rejected =
                assertThrows(FormDefinitionRejected.class, () -> renderer.render(yaml));
        assertTrue(rejected.getMessage().contains("domainAction"));
    }

    @Test
    void unknownDomainActionIsRejected() {
        String yaml = """
                formKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                version: 1
                permission: registry.write
                domainAction: webhook.call
                fields:
                  - name: serviceName
                    kind: text
                    required: true
                  - name: host
                    kind: text
                    required: true
                  - name: port
                    kind: integer
                    required: true
                """;
        FormDefinitionRejected rejected =
                assertThrows(FormDefinitionRejected.class, () -> renderer.render(yaml));
        assertTrue(rejected.getMessage().contains("unknown domainAction"));
    }

    @Test
    void domainActionMissingRequiredFieldIsRejected() {
        String yaml = """
                formKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                version: 1
                permission: registry.write
                domainAction: registry.register
                fields:
                  - name: host
                    kind: text
                    required: true
                """;
        FormDefinitionRejected rejected =
                assertThrows(FormDefinitionRejected.class, () -> renderer.render(yaml));
        assertTrue(rejected.getMessage().contains("requires field"));
    }

    @Test
    void rendersDemoTicketFormWithEntityKey() throws IOException {
        try (var in = FormRendererTest.class.getResourceAsStream("/forms/demo-ticket.form.yaml")) {
            assertTrue(in != null);
            RenderedForm form = renderer.render(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("demo-ticket", form.formKey());
            assertEquals("demo-ticket", form.entityKey());
            assertEquals(DomainActionKey.ENTITY_RECORD_UPSERT, form.domainAction());
            assertEquals(5, form.fields().size());
            assertEquals("ticketId", form.fields().get(0).name());
        }
    }

    @Test
    void entityRecordUpsertWithoutEntityKeyIsRejected() {
        String yaml = """
                formKey: demo-ticket
                titleEn: Demo ticket
                titleZh: 演示工单
                version: 1
                permission: page.read
                domainAction: entity.record.upsert
                fields:
                  - name: ticketId
                    kind: text
                    required: true
                """;
        FormDefinitionRejected rejected =
                assertThrows(FormDefinitionRejected.class, () -> renderer.render(yaml));
        assertTrue(rejected.getMessage().contains("entityKey"));
    }

        static String formYaml() throws IOException {
        try (var in = FormRendererTest.class.getResourceAsStream("/forms/endpoint-publication.form.yaml")) {
            if (in == null) {
                throw new IOException("form definition is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void rendersBooleanDateEnumFields() {
        String yaml = """
                formKey: kinds-sample
                titleEn: Kinds
                titleZh: 种类
                version: 1
                permission: page.read
                domainAction: entity.record.upsert
                entityKey: kinds-sample
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 32
                  - name: urgent
                    kind: boolean
                    required: true
                  - name: dueDate
                    kind: date
                    required: false
                    maxLength: 10
                  - name: status
                    kind: enum
                    required: true
                    enumValues: [open, closed]
                """;
        RenderedForm form = renderer.render(yaml);
        assertEquals(FieldKind.BOOLEAN, form.fields().get(1).kind());
        assertEquals(FieldKind.DATE, form.fields().get(2).kind());
        assertEquals(FieldKind.ENUM, form.fields().get(3).kind());
        assertEquals(List.of("open", "closed"), form.fields().get(3).enumValues());
    }

}
