package com.subjex.platform.app.page;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.platform.app.declaration.DeclarationKind;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.declaration.JdbcDeclarationMigrationStore;
import com.subjex.platform.app.declaration.JdbcDeclarationStore;
import com.subjex.platform.app.form.FormCatalog;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PagesRuntimeAfterPromoteSecurityTest — RT-5：晋升 flow 后带租户头页面目录/详情可见；无声明失败关闭。
 */
@WebMvcTest(controllers = PagesApiEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    PageCatalog.class,
    PagesRuntimeAfterPromoteSecurityTest.SliceConfiguration.class
})
class PagesRuntimeAfterPromoteSecurityTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T02:00:00Z"), ZoneOffset.UTC);

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcDeclarationStore store;

    @Test
    void afterPromotePagesIndexAndDetailExposePaths() throws Exception {
        var draft = store.saveDraft("acme", DeclarationKind.FLOW, "repair-ticket", REPAIR_FLOW, "sub-editor");
        store.markPromoted("acme", DeclarationKind.FLOW, "repair-ticket", draft.revision());
        store.recordPromote(
                "acme", DeclarationKind.FLOW, "repair-ticket", draft.revision(), "sha-rt5", "sub-editor");

        mockMvc.perform(get(PagesApiEndpoint.PATH)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header(TenantEnforcementFilter.TENANT_HEADER, "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages[?(@.flowKey=='repair-ticket')]").isNotEmpty())
                .andExpect(jsonPath("$.pages[?(@.flowKey=='repair-ticket')].permission").value(hasItem("page.read")))
                .andExpect(jsonPath("$.pages[?(@.flowKey=='repair-ticket')].tenantScoped").value(hasItem(true)));

        mockMvc.perform(get(PagesApiEndpoint.PATH + "/repair-ticket")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header(TenantEnforcementFilter.TENANT_HEADER, "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flowKey").value("repair-ticket"))
                .andExpect(jsonPath("$.list.path").value("/pages/repair-ticket"))
                .andExpect(jsonPath("$.submit.path").value("/pages/repair-ticket/new"))
                .andExpect(jsonPath("$.detail.path").value("/pages/repair-ticket/{id}"))
                .andExpect(jsonPath("$.list.blocks[0]").value("ListTable"))
                .andExpect(jsonPath("$.detail.blocks[0]").value("DetailReadonly"))
                .andExpect(jsonPath("$.submit.blocks[0]").value("FormFields"))
                .andExpect(jsonPath("$.submit.blocks[1]").value("SubmitBar"));
    }

    @Test
    void withoutPromoteOrClasspathDetailIsNotFound() throws Exception {
        mockMvc.perform(get(PagesApiEndpoint.PATH + "/repair-ticket")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header(TenantEnforcementFilter.TENANT_HEADER, "acme"))
                .andExpect(status().isNotFound());
    }

    @Test
    void withoutTenantHeaderUnknownKeyIsBadRequest() throws Exception {
        // Classpath catalog.require → IllegalArgumentException → 400 (no overlay without tenant header).
        // 无租户头走 classpath；未知键 400。
        mockMvc.perform(get(PagesApiEndpoint.PATH + "/repair-ticket")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @TestConfiguration
    static class SliceConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        JdbcDeclarationStore jdbcDeclarationStore(JdbcTemplate jdbc) {
            return new JdbcDeclarationStore(jdbc, CLOCK);
        }

        @Bean
        JdbcDeclarationMigrationStore jdbcDeclarationMigrationStore(JdbcTemplate jdbc) {
            return new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        }

        @Bean
        EffectiveDeclarationService effectiveDeclarationService(
                JdbcDeclarationStore jdbcDeclarationStore,
                JdbcDeclarationMigrationStore jdbcDeclarationMigrationStore,
                PageCatalog pageCatalog) {
            return new EffectiveDeclarationService(
                    jdbcDeclarationStore,
                    jdbcDeclarationMigrationStore,
                    EntityCatalog.load(EntityCatalog.class.getClassLoader()),
                    new FormCatalog(),
                    pageCatalog);
        }
    }
}
