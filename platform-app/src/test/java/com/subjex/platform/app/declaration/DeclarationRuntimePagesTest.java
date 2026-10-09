package com.subjex.platform.app.declaration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.page.declare.PageRenderer;
import org.junit.jupiter.api.Test;


/**
 * DeclarationRuntimePagesTest — purpose: flow promote derives list/new/detail bindings (no page YAML).
 * Gates: path contract /pages/{key}; tenantScoped flows need four business-table blocks (fail-closed).
 * <p>
 * 目的：流程晋升派生 list/new/detail 绑定（不另写 page YAML）。门禁：路径契约；租户隔离须四积木。
 */
class DeclarationRuntimePagesTest {

    private static final String REPAIR_FLOW =
            """
            flowKey: repair-ticket
            titleEn: Repair tickets
            titleZh: 报修单
            formKey: repair-ticket
            entityKey: repair-ticket
            version: 1
            permission: page.read
            tenantScoped: true
            list:
              path: /pages/repair-ticket
              apiPath: /api/v1/entities/repair-ticket/records
              itemsKey: records
              blocks:
                - ListTable
            detail:
              path: /pages/repair-ticket/{id}
              apiPath: /api/v1/entities/repair-ticket/records
              itemsKey: records
              idField: ticketId
              blocks:
                - DetailReadonly
            submit:
              path: /pages/repair-ticket/new
              apiPath: /api/v1/forms/repair-ticket/submissions
              redirectTo: /pages/repair-ticket
              blocks:
                - FormFields
                - SubmitBar
            """;

    @Test
    void ensureAcceptsBusinessTableFlow() {
        DeclarationRuntimePages.Binding binding = DeclarationRuntimePages.ensureAfterFlowPromote(REPAIR_FLOW);
        assertEquals("repair-ticket", binding.flowKey());
        assertEquals("/pages/repair-ticket", binding.listPath());
        assertEquals("/pages/repair-ticket/new", binding.newPath());
        assertEquals("/pages/repair-ticket/{id}", binding.detailPath());
        assertEquals("page.read", binding.permission());
        assertTrue(binding.tenantScoped());
    }

    @Test
    void ensureRejectsWrongListPath() {
        String needle = "path: /pages/repair-ticket\n";
        int at = REPAIR_FLOW.indexOf(needle);
        String yaml = REPAIR_FLOW.substring(0, at) + "path: /pages/other\n" + REPAIR_FLOW.substring(at + needle.length());
        assertThrows(IllegalArgumentException.class, () -> DeclarationRuntimePages.ensureAfterFlowPromote(yaml));
    }

    @Test
    void tenantScopedRequiresFourBlocks() {
        String yaml =
                """
                flowKey: repair-ticket
                titleEn: Repair tickets
                titleZh: 报修单
                formKey: repair-ticket
                entityKey: repair-ticket
                version: 1
                permission: page.read
                tenantScoped: true
                list:
                  path: /pages/repair-ticket
                  apiPath: /api/v1/entities/repair-ticket/records
                  itemsKey: records
                detail:
                  path: /pages/repair-ticket/{id}
                  apiPath: /api/v1/entities/repair-ticket/records
                  itemsKey: records
                  idField: ticketId
                submit:
                  path: /pages/repair-ticket/new
                  apiPath: /api/v1/forms/repair-ticket/submissions
                  redirectTo: /pages/repair-ticket
                """;
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> DeclarationRuntimePages.ensureAfterFlowPromote(yaml));
        assertTrue(ex.getMessage().contains("ListTable") || ex.getMessage().contains("blocks"));
    }

    @Test
    void nonTenantScopedAllowsEmptyBlocksWhenPathsMatch() {
        String yaml =
                """
                flowKey: demo-ticket
                titleEn: Demo tickets
                titleZh: 演示工单
                formKey: demo-ticket
                entityKey: demo-ticket
                version: 1
                permission: page.read
                tenantScoped: false
                list:
                  path: /pages/demo-ticket
                  apiPath: /api/v1/entities/demo-ticket/records
                  itemsKey: records
                detail:
                  path: /pages/demo-ticket/{id}
                  apiPath: /api/v1/entities/demo-ticket/records
                  itemsKey: records
                  idField: ticketId
                submit:
                  path: /pages/demo-ticket/new
                  apiPath: /api/v1/forms/demo-ticket/submissions
                  redirectTo: /pages/demo-ticket
                """;
        DeclarationRuntimePages.Binding binding = DeclarationRuntimePages.ensureAfterFlowPromote(yaml);
        assertEquals("/pages/demo-ticket", binding.listPath());
        assertTrue(binding.listBlocks().isEmpty());
    }

    @Test
    void fromFlowMirrorsRenderer() {
        var flow = new PageRenderer().render(REPAIR_FLOW);
        DeclarationRuntimePages.Binding binding = DeclarationRuntimePages.fromFlow(flow);
        assertEquals(flow.list().path(), binding.listPath());
        assertEquals(flow.submit().path(), binding.newPath());
        assertEquals(flow.detail().path(), binding.detailPath());
    }
}
