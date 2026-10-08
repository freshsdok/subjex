package com.subjex.platform.app.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.page.declare.RenderedFlow;
import org.junit.jupiter.api.Test;

/**
 * PageCatalogTest — 页面目录测试：classpath 上的每一份 YAML 都能按 flowKey 找到。
 */
class PageCatalogTest {

    private final PageCatalog catalog = new PageCatalog();

    @Test
    void loadsTheCheckedInEndpointPublicationFlow() {
        RenderedFlow flow = catalog.require("endpoint-publication");
        assertEquals("endpoint-publication", flow.flowKey());
        assertEquals("endpoint-publication", flow.formKey());
        assertEquals("/pages/endpoint-publication", flow.list().path());
        assertEquals("submissionId", flow.detail().idField());
        assertEquals("/pages/endpoint-publication", flow.submit().redirectTo());
        assertEquals(1, flow.version());
        assertEquals("page.read", flow.permission());
        assertEquals(false, flow.tenantScoped());
        assertTrue(catalog.list().stream().anyMatch(f -> f.flowKey().equals("endpoint-publication")));
    }

    @Test
    void loadsTheCheckedInServiceNoteFlowTiedToEntity() {
        RenderedFlow flow = catalog.require("service-note");
        assertEquals("service-note", flow.flowKey());
        assertEquals("service-note", flow.formKey());
        assertEquals("service-note", flow.entityKey());
        assertEquals("/api/v1/entities/service-note/notes", flow.list().apiPath());
        assertEquals("notes", flow.list().itemsKey());
        assertEquals("noteId", flow.detail().idField());
        assertEquals("/api/v1/forms/service-note/submissions", flow.submit().apiPath());
        assertEquals("/pages/service-note", flow.submit().redirectTo());
        assertEquals(1, flow.version());
        assertEquals("page.read", flow.permission());
    }
}

