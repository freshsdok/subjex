package com.subjex.platform.app.declaration;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.platform.app.form.FormCatalog;
import com.subjex.platform.app.form.FormsApiEndpoint;
import com.subjex.platform.app.page.PageCatalog;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * FormsRuntimeOverlaySecurityTest — 带 {@code X-Tenant-Id} 时表单详情走库内草稿覆盖；无头仍 classpath。
 */
@WebMvcTest(controllers = FormsApiEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    FormCatalog.class,
    FormsRuntimeOverlaySecurityTest.SliceConfiguration.class
})
class FormsRuntimeOverlaySecurityTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);

    private static final String FORM_DRAFT =
            """
            formKey: demo-ticket
            titleEn: Demo ticket draft
            titleZh: 演示工单草稿
            version: 9
            permission: page.read
            tenantScoped: false
            domainAction: entity.record.upsert
            entityKey: demo-ticket
            fields:
              - name: ticketId
                kind: text
                required: true
                maxLength: 64
              - name: title
                kind: text
                required: true
                maxLength: 40
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcDeclarationStore store;

    @BeforeEach
    void seedFormDraft() {
        store.saveDraft("acme", DeclarationKind.FORM, "demo-ticket", FORM_DRAFT, "sub-editor");
    }

    @Test
    void withoutTenantHeaderUsesClasspathForm() throws Exception {
        mockMvc.perform(get(FormsApiEndpoint.PATH + "/demo-ticket").with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.titleEn").value("Demo ticket"));
    }

    @Test
    void withTenantHeaderUsesDraftOverlay() throws Exception {
        mockMvc.perform(get(FormsApiEndpoint.PATH + "/demo-ticket")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header(TenantEnforcementFilter.TENANT_HEADER, "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(9))
                .andExpect(jsonPath("$.titleEn").value("Demo ticket draft"))
                .andExpect(jsonPath("$.fields.length()").value(2));
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
        EffectiveDeclarationService effectiveDeclarationService(
                JdbcDeclarationStore jdbcDeclarationStore, FormCatalog formCatalog) {
            return new EffectiveDeclarationService(
                    jdbcDeclarationStore,
                    EntityCatalog.load(EntityCatalog.class.getClassLoader()),
                    formCatalog,
                    new PageCatalog());
        }
    }
}
