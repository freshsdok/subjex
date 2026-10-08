package com.subjex.platform.app.form;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormField;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.extension.TaskDeliveryExtension;
import com.subjex.platform.contract.extension.PlatformExtension;
import com.subjex.platform.contract.task.TaskMessagePort;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.capability.CapabilityRunner;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.entity.declare.EntityCatalog;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.declaration.JdbcDeclarationStore;
import com.subjex.platform.app.page.PageCatalog;
import com.subjex.platform.app.entity.GenericEntityStore;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * DeclarationSecurityTest — 声明权限安全测试：缺权限或租户隔离缺头时拒绝；不改安全配置也能按 YAML 卡死。
 */
@WebMvcTest(controllers = {FormsApiEndpoint.class, FormSubmissionEndpoint.class})
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    DeclarationSecurityTest.SliceConfiguration.class
})
class DeclarationSecurityTest {

    private static final String OPERATOR = OperatorDirectoryTestConfiguration.OPERATOR;
    private static final String OPERATOR_PASSWORD = OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
    private static final String VIEWER = OperatorDirectoryTestConfiguration.VIEWER;
    private static final String VIEWER_PASSWORD = OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServiceCatalog serviceCatalog;

    @MockitoBean
    private ConfigCatalog configCatalog;

    @MockitoBean
    private OperatorActionAudit operatorActionAudit;

    @MockitoBean
    private FormSubmissionStore formSubmissionStore;

    @MockitoBean
    private TaskMessagePort taskMessagePort;

    @Test
    void wrongPermissionIsDeniedOnFormDetailAndSubmit() throws Exception {
        String detail = JsonApi.BASE + "/forms/tenant-note";
        mockMvc.perform(get(detail).with(httpBasic(VIEWER, VIEWER_PASSWORD))).andExpect(status().isForbidden());
        mockMvc.perform(get(detail).with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header(TenantEnforcementFilter.TENANT_HEADER, "acme"))
                .andExpect(status().isOk());

        String submit = JsonApi.BASE + "/forms/tenant-note/submissions";
        String body = "{\"values\":{\"title\":\"hello\"}}";
        mockMvc.perform(post(submit)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .header(TenantEnforcementFilter.TENANT_HEADER, "acme"))
                .andExpect(status().isForbidden());
        verify(formSubmissionStore, never()).save(anyString(), anyInt(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void tenantScopedFormWithoutTenantHeaderIsDenied() throws Exception {
        String detail = JsonApi.BASE + "/forms/tenant-note";
        mockMvc.perform(get(detail).with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(detail)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header(TenantEnforcementFilter.TENANT_HEADER, "acme"))
                .andExpect(status().isOk());
    }

    @TestConfiguration
    static class SliceConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        FormSideEffectRunner formSideEffectRunner(
                OperatorActionAudit operatorActionAudit, TaskMessagePort taskMessagePort) {
            return new FormSideEffectRunner(
                    operatorActionAudit, taskMessagePort, List.of(new TaskDeliveryExtension()));
        }

        @Bean
        EffectiveDeclarationService effectiveDeclarationService(FormCatalog formCatalog) {
            JdbcDeclarationStore store = mock(JdbcDeclarationStore.class);
            when(store.latest(any(), any(), any())).thenReturn(Optional.empty());
            return new EffectiveDeclarationService(
                    store,
                    EntityCatalog.load(EntityCatalog.class.getClassLoader()),
                    formCatalog,
                    new PageCatalog());
        }

        @Bean
        FormDomainActionRunner formDomainActionRunner(
                ServiceCatalog serviceCatalog,
                ConfigCatalog configCatalog,
                EffectiveDeclarationService effectiveDeclarationService) {
            return new FormDomainActionRunner(
                    serviceCatalog,
                    configCatalog,
                    effectiveDeclarationService,
                    mock(GenericEntityStore.class),
                    new CapabilityRunner(new CapabilityCatalog()));
        }

        @Bean
        PlatformExtension taskDeliveryExtension() {
            return new TaskDeliveryExtension();
        }

        /**
         * Synthetic tenant-scoped form declaring registry.write — 合成的租户隔离表单，声明 registry.write。
         * Not a classpath YAML; proves API enforcement without a new sample form.
         * 不是 classpath YAML；证明 API 强制而不新增样例表单。
         */
        @Bean
        FormCatalog formCatalog() {
            RenderedForm scoped = new RenderedForm(
                    "tenant-note",
                    "Tenant note",
                    "租户备注",
                    1,
                    "registry.write",
                    true,
                    DomainActionKey.REGISTRY_REGISTER,
                    null,
                    "TenantNote",
                    List.of(new FormField("title", FieldKind.TEXT, true, null, null, 64, List.of())),
                    List.of());
            return new FormCatalog(Map.of("tenant-note", scoped));
        }
    }
}
