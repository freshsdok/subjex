package com.subjex.form.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FormRendererTest {

    private final FormRenderer renderer = new FormRenderer();

    @Test
    void rendersTheCheckedInFormAsAValidatedFieldList() throws IOException {
        RenderedForm form = renderer.render(formYaml());

        assertEquals("endpoint-publication", form.formKey());
        assertEquals("EndpointPublication", form.recordName());
        assertEquals(3, form.fields().size());
        assertEquals(new FormField("serviceName", FieldKind.TEXT, true, null, null, 64), form.fields().get(0));
        assertEquals(new FormField("host", FieldKind.TEXT, true, null, null, 253), form.fields().get(1));
        assertEquals(new FormField("port", FieldKind.INTEGER, true, 1, 65535, null), form.fields().get(2));
    }

    @Test
    void duplicateFieldNameIsRejected() {
        String yaml = """
                formKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
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
                fields:
                  - name: host
                    kind: token
                    required: true
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
