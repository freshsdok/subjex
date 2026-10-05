package com.subjex.platform.app.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.admin.AuditApiEndpoint;
import com.subjex.platform.app.codegen.CodegenApiEndpoint;
import com.subjex.platform.app.config.ConfigApiEndpoint;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.platform.app.deploy.DeployApiEndpoint;
import com.subjex.platform.app.deploy.ManifestCatalog;
import com.subjex.platform.app.discovery.ListedService;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.app.discovery.ServiceListApiEndpoint;
import com.subjex.platform.app.form.FormCatalog;
import com.subjex.platform.app.form.FormSubmissionEndpoint;
import com.subjex.platform.app.form.FormsApiEndpoint;
import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.language.LanguageApiEndpoint;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.OperatorSelfEndpoint;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.skin.SkinApiEndpoint;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.config.ConfigEntry;
import com.subjex.platform.contract.config.ConfigOrigin;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * JsonApiSecurityTest — JSON 接口安全测试：{@code /api/v1} 下每个接口都要求登录，并且只认对应的具名权限。
 * <p>
 * Proves, for every one of the nine JSON controllers: a permitted operator gets 200 and the document's key
 * field; no credentials gives 401; an operator without the permission gets 403. For the config override it
 * also proves a blank value is refused with 400 and a successful override records a {@code config.override}
 * audit entry.
 * 对九个 JSON 控制器逐一证明：有权限的操作员拿到 200 和文档的关键字段；不带凭据是 401；缺权限的操作员是 403。
 * 对配置覆盖，另外证明空白值以 400 拒绝，成功覆盖会留下一条 {@code config.override} 审计。
 */
@WebMvcTest(controllers = {
    OperatorSelfEndpoint.class,
    ServiceListApiEndpoint.class,
    ConfigApiEndpoint.class,
    AuditApiEndpoint.class,
    DeployApiEndpoint.class,
    FormsApiEndpoint.class,
    FormSubmissionEndpoint.class,
    CodegenApiEndpoint.class,
    LanguageApiEndpoint.class,
    SkinApiEndpoint.class
})
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    FormCatalog.class,
    ManifestCatalog.class,
    JsonApiSecurityTest.SliceConfiguration.class
})
class JsonApiSecurityTest {

    private static final String OPERATOR = OperatorDirectoryTestConfiguration.OPERATOR;
    private static final String OPERATOR_PASSWORD = OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
    private static final String VIEWER = OperatorDirectoryTestConfiguration.VIEWER;
    private static final String VIEWER_PASSWORD = OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
    /** An operator holding no role at all — 一个没有任何角色的操作员。 */
    private static final String BARE = "platform-bare";
    private static final String BARE_PASSWORD = "bare-pass";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private ServiceCatalog serviceCatalog;

    @MockitoBean
    private ConfigCatalog configCatalog;

    @MockitoBean
    private OperatorActionAudit operatorActionAudit;

    @BeforeEach
    void bareOperatorAndCatalogs() {
        Integer present = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE login_name = ?", Integer.class, BARE);
        if (present == null || present == 0) {
            jdbc.update("INSERT INTO account (account_id, login_name, account_state) VALUES ('account-bare', ?, 'ACTIVE')",
                    BARE);
            jdbc.update("INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES ('subject-bare', 'Bare', 'PERSON')");
            jdbc.update("""
                    INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                    VALUES ('identity-bare', 'account-bare', 'subject-bare', 'platform', 'ACTIVE')
                    """);
            jdbc.update("INSERT INTO operator_credential (account_id, password_hash) VALUES ('account-bare', ?)",
                    passwordEncoder.encode(BARE_PASSWORD));
        }
        when(serviceCatalog.list()).thenReturn(List.of(new ListedService("billing", "10.0.0.7", 8080, "up")));
        when(configCatalog.list()).thenReturn(List.of(new ConfigEntry("subjex.greeting", "hello", ConfigOrigin.LOCAL)));
        when(configCatalog.entry("subjex.greeting"))
                .thenReturn(Optional.of(new ConfigEntry("subjex.greeting", "hi", ConfigOrigin.OVERRIDE)));
    }

    @Test
    void meNeedsOnlyASignedInOperator() throws Exception {
        mockMvc.perform(get(JsonApi.BASE + "/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(JsonApi.BASE + "/me").with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginName").value(OPERATOR))
                .andExpect(jsonPath("$.permissions").isArray())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mockMvc.perform(get(JsonApi.BASE + "/me").with(httpBasic(BARE, BARE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginName").value(BARE))
                .andExpect(jsonPath("$.permissions").isEmpty());
    }

    @Test
    void servicesNeedRegistryRead() throws Exception {
        String path = JsonApi.BASE + "/services";
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).with(httpBasic(BARE, BARE_PASSWORD))).andExpect(status().isForbidden());
        mockMvc.perform(get(path).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0].serviceName").value("billing"))
                .andExpect(jsonPath("$.services[0].status").value("up"));
    }

    @Test
    void configReadNeedsConfigRead() throws Exception {
        String path = JsonApi.BASE + "/config";
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).with(httpBasic(BARE, BARE_PASSWORD))).andExpect(status().isForbidden());
        mockMvc.perform(get(path).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].key").value("subjex.greeting"))
                .andExpect(jsonPath("$.entries[0].origin").value("local"));
    }

    @Test
    void configOverrideNeedsConfigWriteRefusesBlankAndIsAudited() throws Exception {
        String path = JsonApi.BASE + "/config/subjex.greeting";
        mockMvc.perform(override(path, "{\"value\":\"hi\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(override(path, "{\"value\":\"hi\"}").with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(override(path, "{\"value\":\"hi\"}").with(httpBasic(BARE, BARE_PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(override(path, "{\"value\":\"   \"}").with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest());
        verify(configCatalog, never()).override(anyString(), anyString());
        verify(operatorActionAudit, never()).record(any(), anyString(), anyString(), any());

        mockMvc.perform(override(path, "{\"value\":\"hi\"}").with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("subjex.greeting"))
                .andExpect(jsonPath("$.value").value("hi"))
                .andExpect(jsonPath("$.origin").value("override"));
        verify(configCatalog).override("subjex.greeting", "hi");
        verify(operatorActionAudit).record(
                any(), eq(OperatorActionAudit.CONFIG_OVERRIDE), eq("subjex.greeting"), eq(AuditOutcome.ALLOWED));
    }

    @Test
    void auditNeedsAdminRead() throws Exception {
        String path = JsonApi.BASE + "/audit";
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).with(httpBasic(BARE, BARE_PASSWORD))).andExpect(status().isForbidden());
        mockMvc.perform(get(path).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray());
    }

    @Test
    void pageTwinsNeedPageRead() throws Exception {
        assertPageRead("/deploy", "$.applied");
        assertPageRead("/forms", "$.formKey");
        assertPageRead("/codegen", "$.recordName");
        assertPageRead("/language", "$.languages");
        assertPageRead("/skins", "$.skins");
    }

    private void assertPageRead(String suffix, String keyField) throws Exception {
        String path = JsonApi.BASE + suffix;
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).with(httpBasic(BARE, BARE_PASSWORD))).andExpect(status().isForbidden());
        mockMvc.perform(get(path).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(keyField).exists());
    }


    @Test
    void formSubmissionNeedsRegistryWriteRefusesBadFieldsAndIsAudited() throws Exception {
        String path = JsonApi.BASE + "/forms/endpoint-publication/submissions";
        String body = "{\"values\":{\"serviceName\":\"billing\",\"host\":\"10.0.0.8\",\"port\":8080}}";
        mockMvc.perform(submit(path, body)).andExpect(status().isUnauthorized());
        mockMvc.perform(submit(path, body).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(submit(path, body).with(httpBasic(BARE, BARE_PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(submit(path, "{\"values\":{\"serviceName\":\"\",\"host\":\"10.0.0.8\",\"port\":8080}}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest());
        verify(serviceCatalog, never()).register(any());
        verify(operatorActionAudit, never()).record(any(), eq(OperatorActionAudit.REGISTRY_REGISTER), anyString(), any());

        mockMvc.perform(submit(path, body).with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formKey").value("endpoint-publication"))
                .andExpect(jsonPath("$.serviceName").value("billing"))
                .andExpect(jsonPath("$.host").value("10.0.0.8"))
                .andExpect(jsonPath("$.port").value(8080));
        verify(serviceCatalog).register(new ServiceEndpoint("billing", "10.0.0.8", 8080));
        verify(operatorActionAudit).record(
                any(), eq(OperatorActionAudit.REGISTRY_REGISTER), eq("billing"), eq(AuditOutcome.ALLOWED));
    }

    private static MockHttpServletRequestBuilder submit(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder override(String path, String body) {
        return put(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @TestConfiguration
    static class SliceConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        JdbcAdminReader jdbcAdminReader(JdbcTemplate jdbc) {
            return new JdbcAdminReader(jdbc);
        }
    }
}
