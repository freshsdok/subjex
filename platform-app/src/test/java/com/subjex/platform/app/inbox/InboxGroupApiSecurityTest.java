package com.subjex.platform.app.inbox;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.tenant.TenantIsolationTags;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.inbox.InboxGroupPort;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * InboxGroupApiSecurityTest — IM-A3: group CRUD AuthZ + tenant fail-closed.
 * 组 CRUD 权限与租户失败关闭。
 */
@WebMvcTest(controllers = InboxGroupEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    InboxGroupApiSecurityTest.Config.class
})
@Tag(TenantIsolationTags.TENANT_ISOLATION)
class InboxGroupApiSecurityTest {

    private static final String TENANT = "tenant-inbox-a3";
    private static final String OTHER = "tenant-inbox-a3-other";
    private static final String VIEWER_SUBJECT = "subject-viewer";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OperatorTenantAccess tenantAccess;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
        ensureTenant(TENANT);
        ensureTenant(OTHER);
        tenantAccess.ensureGrant(LocalOperatorSeeder.SUBJECT_ID, OperatorTenantAccess.ALL_TENANTS);
        tenantAccess.ensureGrant(VIEWER_SUBJECT, TENANT);
    }

    private void ensureTenant(String id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, id);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')", id, id);
        }
    }

    @Test
    void happyPathCreateListUpdateMembersDelete() throws Exception {
        MvcResult created = mockMvc.perform(post(InboxGroupEndpoint.BASE)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"值班组","description":"夜班"}
                                """)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").isNotEmpty())
                .andExpect(jsonPath("$.name").value("值班组"))
                .andExpect(jsonPath("$.realm").value("OPERATOR"))
                .andReturn();
        String groupId =
                objectMapper.readTree(created.getResponse().getContentAsString()).get("groupId").asText();

        mockMvc.perform(get(InboxGroupEndpoint.BASE)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups", hasSize(1)))
                .andExpect(jsonPath("$.groups[0].groupId").value(groupId));

        mockMvc.perform(patch(InboxGroupEndpoint.BASE + "/" + groupId)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"值班组-改","description":null}
                                """)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("值班组-改"));

        mockMvc.perform(post(InboxGroupEndpoint.BASE + "/" + groupId + "/members")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberSubjectId":"%s"}
                                """.formatted(VIEWER_SUBJECT))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberSubjectId").value(VIEWER_SUBJECT));

        // idempotent add
        mockMvc.perform(post(InboxGroupEndpoint.BASE + "/" + groupId + "/members")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberSubjectId":"%s"}
                                """.formatted(VIEWER_SUBJECT))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk());

        mockMvc.perform(get(InboxGroupEndpoint.BASE + "/" + groupId + "/members")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members", hasSize(1)));

        mockMvc.perform(delete(InboxGroupEndpoint.BASE + "/" + groupId + "/members/" + VIEWER_SUBJECT)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(InboxGroupEndpoint.BASE + "/" + groupId + "/members")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members", hasSize(0)));

        mockMvc.perform(delete(InboxGroupEndpoint.BASE + "/" + groupId)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(InboxGroupEndpoint.BASE + "/" + groupId)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewerWithoutGroupAdminIsForbidden() throws Exception {
        mockMvc.perform(get(InboxGroupEndpoint.BASE)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(InboxGroupEndpoint.BASE)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"拒绝\"}")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantGroupIsNotVisible() throws Exception {
        MvcResult created = mockMvc.perform(post(InboxGroupEndpoint.BASE)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"仅本租户\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String groupId =
                objectMapper.readTree(created.getResponse().getContentAsString()).get("groupId").asText();

        mockMvc.perform(get(InboxGroupEndpoint.BASE + "/" + groupId)
                        .header(TenantEnforcementFilter.TENANT_HEADER, OTHER)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(InboxGroupEndpoint.BASE)
                        .header(TenantEnforcementFilter.TENANT_HEADER, OTHER)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups", hasSize(0)));
    }

    @Test
    void missingTenantIsForbidden() throws Exception {
        mockMvc.perform(get(InboxGroupEndpoint.BASE).with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(InboxGroupEndpoint.BASE).header(TenantEnforcementFilter.TENANT_HEADER, TENANT))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class Config {

        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        TransactionTemplate transactionTemplate(DataSource operatorTables) {
            return new TransactionTemplate(new DataSourceTransactionManager(operatorTables));
        }

        @Bean
        InboxGroupPort inboxGroupPort(JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
            return new JdbcInboxGroupPort(jdbc, transactionTemplate);
        }

        @Bean
        InboxGroupService inboxGroupService(
                InboxGroupPort inboxGroupPort, OperatorTenantAccess tenantAccess, TenantGuard tenantGuard) {
            return new InboxGroupService(inboxGroupPort, tenantAccess, tenantGuard, Clock.systemUTC());
        }
    }
}
