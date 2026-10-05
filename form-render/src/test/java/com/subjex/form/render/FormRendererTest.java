package com.subjex.form.render;

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
        assertEquals(1, form.version());
        assertEquals("registry.write", form.permission());
        assertFalse(form.tenantScoped());
        assertEquals("EndpointPublication", form.recordName());
        assertEquals(3, form.fields().size());
        assertEquals(new FormField("serviceName", FieldKind.TEXT, true, null, null, 64), form.fields().get(0));
        assertEquals(new FormField("host", FieldKind.TEXT, true, null, null, 253), form.fields().get(1));
        assertEquals(new FormField("port", FieldKind.INTEGER, true, 1, 65535, null), form.fields().get(2));
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
                fields:
                  - name: host
                    kind: text
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
                fields:
                  - name: host
                    kind: text
                    required: true
                effects:
                  - key: audit.write
                    params: {}
                """;
        assertThrows(FormDefinitionRejected.class, () -> renderer.render(yaml));
    }

    static String formYaml() throws IOException {
        try (var in = FormRendererTest.class.getResourceAsStream("/forms/endpoint-publication.form.yaml")) {
            if (in == null) {
                throw new IOException("form definition is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
