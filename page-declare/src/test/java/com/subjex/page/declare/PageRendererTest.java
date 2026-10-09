package com.subjex.page.declare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageRendererTest {

    private final PageRenderer renderer = new PageRenderer();

    @Test
    void rendersTheCheckedInFlowAsValidatedPageSpecs() throws IOException {
        RenderedFlow flow = renderer.render(flowYaml());

        assertEquals("endpoint-publication", flow.flowKey());
        assertEquals("Endpoint publication", flow.titleEn());
        assertEquals("端点发布", flow.titleZh());
        assertEquals("endpoint-publication", flow.formKey());
        assertNull(flow.entityKey());
        assertEquals(1, flow.version());
        assertEquals("page.read", flow.permission());
        assertFalse(flow.tenantScoped());
        assertEquals("/pages/endpoint-publication", flow.list().path());
        assertEquals("/api/v1/forms/endpoint-publication/submissions", flow.list().apiPath());
        assertEquals("submissions", flow.list().itemsKey());
        assertEquals("/pages/endpoint-publication/{id}", flow.detail().path());
        assertEquals("submissionId", flow.detail().idField());
        assertEquals("/pages/endpoint-publication/new", flow.submit().path());
        assertEquals("/pages/endpoint-publication", flow.submit().redirectTo());
        assertTrue(flow.list().blocks().isEmpty());
        assertTrue(flow.detail().blocks().isEmpty());
        assertTrue(flow.submit().blocks().isEmpty());
        assertNull(flow.submit().capabilityId());
    }


    @Test
    void rendersTheServiceNoteFlowWithEntityKey() throws IOException {
        try (var in = PageRendererTest.class.getResourceAsStream("/flows/service-note.flow.yaml")) {
            if (in == null) {
                throw new IOException("service-note flow is not on the classpath");
            }
            RenderedFlow flow = renderer.render(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("service-note", flow.flowKey());
            assertEquals("service-note", flow.formKey());
            assertEquals("service-note", flow.entityKey());
            assertEquals("notes", flow.list().itemsKey());
            assertEquals("noteId", flow.detail().idField());
            assertEquals("/api/v1/entities/service-note/notes", flow.list().apiPath());
        }
    }

    @Test
    void formKeyMayBeOmitted() {
        String yaml = """
                flowKey: demo-item
                titleEn: Demo
                titleZh: 演示
                version: 1
                permission: page.read
                list:
                  path: /pages/demo-item
                  apiPath: /api/v1/demo/items
                detail:
                  path: /pages/demo-item/{id}
                  apiPath: /api/v1/demo/items
                  idField: itemId
                submit:
                  path: /pages/demo-item/new
                  apiPath: /api/v1/demo/items
                  redirectTo: /pages/demo-item
                """;
        RenderedFlow flow = renderer.render(yaml);
        assertNull(flow.formKey());
        assertNull(flow.list().itemsKey());
        assertEquals("demo-item", flow.flowKey());
        assertEquals(1, flow.version());
        assertEquals("page.read", flow.permission());
        assertFalse(flow.tenantScoped());
    }

    @Test
    void missingPermissionIsRejected() {
        String yaml = """
                flowKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                version: 1
                list:
                  path: /pages/endpoint-publication
                  apiPath: /api/v1/forms/endpoint-publication/submissions
                detail:
                  path: /pages/endpoint-publication/{id}
                  apiPath: /api/v1/forms/endpoint-publication/submissions
                  idField: submissionId
                submit:
                  path: /pages/endpoint-publication/new
                  apiPath: /api/v1/forms/endpoint-publication/submissions
                  redirectTo: /pages/endpoint-publication
                """;
        assertThrows(PageDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void missingVersionIsRejected() {
        String yaml = """
                flowKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                permission: page.read
                list:
                  path: /pages/endpoint-publication
                  apiPath: /api/v1/forms/endpoint-publication/submissions
                detail:
                  path: /pages/endpoint-publication/{id}
                  apiPath: /api/v1/forms/endpoint-publication/submissions
                  idField: submissionId
                submit:
                  path: /pages/endpoint-publication/new
                  apiPath: /api/v1/forms/endpoint-publication/submissions
                  redirectTo: /pages/endpoint-publication
                """;
        assertThrows(PageDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void unknownFlowKeyIsRejected() {
        String yaml = """
                flowKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                version: 1
                permission: page.read
                designer: true
                list:
                  path: /pages/endpoint-publication
                  apiPath: /api/v1/forms/endpoint-publication/submissions
                detail:
                  path: /pages/endpoint-publication/{id}
                  apiPath: /api/v1/forms/endpoint-publication/submissions
                  idField: submissionId
                submit:
                  path: /pages/endpoint-publication/new
                  apiPath: /api/v1/forms/endpoint-publication/submissions
                  redirectTo: /pages/endpoint-publication
                """;
        assertThrows(PageDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void badApiPathIsRejected() {
        String yaml = """
                flowKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                version: 1
                permission: page.read
                list:
                  path: /pages/endpoint-publication
                  apiPath: https://evil.example/api
                detail:
                  path: /pages/endpoint-publication/{id}
                  apiPath: /api/v1/forms/endpoint-publication/submissions
                  idField: submissionId
                submit:
                  path: /pages/endpoint-publication/new
                  apiPath: /api/v1/forms/endpoint-publication/submissions
                  redirectTo: /pages/endpoint-publication
                """;
        assertThrows(PageDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void detailPathMustIncludeIdPlaceholder() {
        String yaml = """
                flowKey: endpoint-publication
                titleEn: Endpoint publication
                titleZh: 端点发布
                version: 1
                permission: page.read
                list:
                  path: /pages/endpoint-publication
                  apiPath: /api/v1/forms/endpoint-publication/submissions
                detail:
                  path: /pages/endpoint-publication
                  apiPath: /api/v1/forms/endpoint-publication/submissions
                  idField: submissionId
                submit:
                  path: /pages/endpoint-publication/new
                  apiPath: /api/v1/forms/endpoint-publication/submissions
                  redirectTo: /pages/endpoint-publication
                """;
        assertThrows(PageDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void blankDefinitionIsRejected() {
        assertThrows(PageDefinitionRejected.class, () -> renderer.render("   "));
    }


    @Test
    void rendersOptionalBlocksInOrder() {
        String yaml = """
                flowKey: demo-item
                titleEn: Demo
                titleZh: 演示
                version: 1
                permission: page.read
                list:
                  path: /pages/demo-item
                  apiPath: /api/v1/demo/items
                  blocks:
                    - Section
                    - ListTable
                detail:
                  path: /pages/demo-item/{id}
                  apiPath: /api/v1/demo/items
                  idField: itemId
                  blocks:
                    - DetailReadonly
                submit:
                  path: /pages/demo-item/new
                  apiPath: /api/v1/demo/items
                  redirectTo: /pages/demo-item
                  blocks:
                    - FormFields
                    - SubmitBar
                """;
        RenderedFlow flow = renderer.render(yaml);
        assertEquals(List.of("Section", "ListTable"), flow.list().blocks());
        assertEquals(List.of("DetailReadonly"), flow.detail().blocks());
        assertEquals(List.of("FormFields", "SubmitBar"), flow.submit().blocks());
    }

    @Test
    void emptyBlocksListIsAccepted() {
        String yaml = """
                flowKey: demo-item
                titleEn: Demo
                titleZh: 演示
                version: 1
                permission: page.read
                list:
                  path: /pages/demo-item
                  apiPath: /api/v1/demo/items
                  blocks: []
                detail:
                  path: /pages/demo-item/{id}
                  apiPath: /api/v1/demo/items
                  idField: itemId
                submit:
                  path: /pages/demo-item/new
                  apiPath: /api/v1/demo/items
                  redirectTo: /pages/demo-item
                """;
        RenderedFlow flow = renderer.render(yaml);
        assertTrue(flow.list().blocks().isEmpty());
    }

    @Test
    void unknownBlockIdIsRejected() {
        String yaml = """
                flowKey: demo-item
                titleEn: Demo
                titleZh: 演示
                version: 1
                permission: page.read
                list:
                  path: /pages/demo-item
                  apiPath: /api/v1/demo/items
                  blocks:
                    - NotARealBlock
                detail:
                  path: /pages/demo-item/{id}
                  apiPath: /api/v1/demo/items
                  idField: itemId
                submit:
                  path: /pages/demo-item/new
                  apiPath: /api/v1/demo/items
                  redirectTo: /pages/demo-item
                """;
        assertThrows(PageDefinitionRejected.class, () -> renderer.render(yaml));
    }

    @Test
    void duplicateBlockIdIsRejected() {
        String yaml = """
                flowKey: demo-item
                titleEn: Demo
                titleZh: 演示
                version: 1
                permission: page.read
                list:
                  path: /pages/demo-item
                  apiPath: /api/v1/demo/items
                  blocks:
                    - ListTable
                    - ListTable
                detail:
                  path: /pages/demo-item/{id}
                  apiPath: /api/v1/demo/items
                  idField: itemId
                submit:
                  path: /pages/demo-item/new
                  apiPath: /api/v1/demo/items
                  redirectTo: /pages/demo-item
                """;
        assertThrows(PageDefinitionRejected.class, () -> renderer.render(yaml));
    }


    @Test
    void submitCapabilityIdIsAcceptedWhenWellFormed() {
        String yaml = """
                flowKey: demo-item
                titleEn: Demo
                titleZh: 演示
                version: 1
                permission: page.read
                list:
                  path: /pages/demo-item
                  apiPath: /api/v1/demo/items
                detail:
                  path: /pages/demo-item/{id}
                  apiPath: /api/v1/demo/items
                  idField: itemId
                submit:
                  path: /pages/demo-item/new
                  apiPath: /api/v1/demo/items
                  redirectTo: /pages/demo-item
                  capabilityId: algo.hashFingerprint
                """;
        RenderedFlow flow = renderer.render(yaml);
        assertEquals("algo.hashFingerprint", flow.submit().capabilityId());
    }

    @Test
    void submitCapabilityIdAiPrefixIsAccepted() {
        String yaml = """
                flowKey: demo-item
                titleEn: Demo
                titleZh: 演示
                version: 1
                permission: page.read
                list:
                  path: /pages/demo-item
                  apiPath: /api/v1/demo/items
                detail:
                  path: /pages/demo-item/{id}
                  apiPath: /api/v1/demo/items
                  idField: itemId
                submit:
                  path: /pages/demo-item/new
                  apiPath: /api/v1/demo/items
                  redirectTo: /pages/demo-item
                  capabilityId: ai.suggestTitlePreview
                """;
        assertEquals("ai.suggestTitlePreview", renderer.render(yaml).submit().capabilityId());
    }

    @Test
    void submitCapabilityIdBadFormatIsRejected() {
        String base = """
                flowKey: demo-item
                titleEn: Demo
                titleZh: 演示
                version: 1
                permission: page.read
                list:
                  path: /pages/demo-item
                  apiPath: /api/v1/demo/items
                detail:
                  path: /pages/demo-item/{id}
                  apiPath: /api/v1/demo/items
                  idField: itemId
                submit:
                  path: /pages/demo-item/new
                  apiPath: /api/v1/demo/items
                  redirectTo: /pages/demo-item
                  capabilityId: %s
                """;
        for (String bad : List.of(
                "hashFingerprint",
                "algo.hash_fingerprint",
                "algo.",
                "foo.bar",
                "algo.HashFingerprint")) {
            assertThrows(PageDefinitionRejected.class, () -> renderer.render(base.formatted(bad)));
        }
    }

    @Test
    void submitUnknownKeyIsStillRejected() {
        String yaml = """
                flowKey: demo-item
                titleEn: Demo
                titleZh: 演示
                version: 1
                permission: page.read
                list:
                  path: /pages/demo-item
                  apiPath: /api/v1/demo/items
                detail:
                  path: /pages/demo-item/{id}
                  apiPath: /api/v1/demo/items
                  idField: itemId
                submit:
                  path: /pages/demo-item/new
                  apiPath: /api/v1/demo/items
                  redirectTo: /pages/demo-item
                  notARealKey: true
                """;
        assertThrows(PageDefinitionRejected.class, () -> renderer.render(yaml));
    }

    static String flowYaml() throws IOException {
        try (var in = PageRendererTest.class.getResourceAsStream("/flows/endpoint-publication.flow.yaml")) {
            if (in == null) {
                throw new IOException("flow definition is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
