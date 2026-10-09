package com.subjex.platform.app.security;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;


/**
 * OperatorManagementSecurityTest — purpose: operator management API permissions/behaviours.
 * Gates: {@code operator.manage} required; self-disable refuse; tenant grant replace audited.
 * <p>
 * 目的：操作员管理接口权限与行为。门禁：需 operator.manage；禁止自停用；租户授权替换可审计。
 */
@WebMvcTest(controllers = {OperatorManagementEndpoint.class, OperatorSelfEndpoint.class})
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OperatorManagementSecurityTest.GuardConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OperatorManagementSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void readerCannotListOrCreateOperators() throws Exception {
        mockMvc.perform(get(OperatorManagementEndpoint.PATH).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(OperatorManagementEndpoint.PATH)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"ops-new\",\"password\":\"long-enough\",\"roleName\":\"platform-reader\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCanListCreateDisableAndGrantTenants() throws Exception {
        mockMvc.perform(get(OperatorManagementEndpoint.PATH).with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operators[*].loginName", hasItem(OPERATOR)));

        mockMvc.perform(post(OperatorManagementEndpoint.PATH)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"ops-new\",\"password\":\"long-enough\",\"roleName\":\"platform-reader\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginName").value("ops-new"))
                .andExpect(jsonPath("$.accountState").value("ACTIVE"));

        mockMvc.perform(put(OperatorManagementEndpoint.PATH + "/ops-new/tenants")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantIds\":[\"tenant-north\"]}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(OperatorManagementEndpoint.PATH + "/ops-new/tenants")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantIds[0]").value("tenant-north"));

        mockMvc.perform(post(OperatorManagementEndpoint.PATH + "/ops-new/disable")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertEquals(
                "DISABLED",
                jdbc.queryForObject(
                        "SELECT account_state FROM account WHERE login_name = 'ops-new'", String.class));

        mockMvc.perform(post(OperatorManagementEndpoint.PATH + "/ops-new/enable")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertEquals(
                "ACTIVE",
                jdbc.queryForObject(
                        "SELECT account_state FROM account WHERE login_name = 'ops-new'", String.class));
    }

    @Test
    void changeOwnPasswordRequiresCurrentAndUpdatesHash() throws Exception {
        mockMvc.perform(post(OperatorManagementEndpoint.PATH + "/me/password")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"new-long-enough\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(OperatorManagementEndpoint.PATH + "/me/password")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"change-me\",\"newPassword\":\"new-long-enough\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(OperatorSelfEndpoint.PATH).with(httpBasic(OPERATOR, "new-long-enough")))
                .andExpect(status().isOk());
        mockMvc.perform(get(OperatorSelfEndpoint.PATH).with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCanChangeOwnPasswordWithoutOperatorManage() throws Exception {
        mockMvc.perform(post(OperatorManagementEndpoint.PATH + "/me/password")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"viewer-pass\",\"newPassword\":\"viewer-new1\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(OperatorSelfEndpoint.PATH).with(httpBasic(VIEWER, "viewer-new1")))
                .andExpect(status().isOk());
    }

    @Test
    void cannotDisableSelf() throws Exception {
        mockMvc.perform(post(OperatorManagementEndpoint.PATH + "/" + OPERATOR + "/disable")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
