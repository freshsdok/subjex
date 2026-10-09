package com.subjex.platform.app.security;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import org.junit.jupiter.api.Assertions;
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
 * Tenant management API permissions and behaviours — 租户管理接口的权限与行为。
 */
@WebMvcTest(controllers = TenantManagementEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    TenantManagementSecurityTest.GuardConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TenantManagementSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcTenantAdmin tenantAdmin;

    @Test
    void readerCanListButCannotCreate() throws Exception {
        mockMvc.perform(get(TenantManagementEndpoint.PATH).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants[*].tenantId", hasItem("platform")));

        mockMvc.perform(post(TenantManagementEndpoint.PATH)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-north\",\"tenantName\":\"North\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCanCreateRenameDisableEnable() throws Exception {
        mockMvc.perform(post(TenantManagementEndpoint.PATH)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-north\",\"tenantName\":\"North\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value("tenant-north"))
                .andExpect(jsonPath("$.tenantName").value("North"))
                .andExpect(jsonPath("$.tenantState").value("ACTIVE"));

        mockMvc.perform(get(TenantManagementEndpoint.PATH + "/tenant-north")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantName").value("North"));

        mockMvc.perform(patch(TenantManagementEndpoint.PATH + "/tenant-north")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantName\":\"North Market\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantName").value("North Market"));

        mockMvc.perform(post(TenantManagementEndpoint.PATH + "/tenant-north/disable")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNoContent());
        Assertions.assertEquals(
                "SUSPENDED",
                jdbc.queryForObject(
                        "SELECT tenant_state FROM tenant WHERE tenant_id = 'tenant-north'", String.class));

        mockMvc.perform(post(TenantManagementEndpoint.PATH + "/tenant-north/enable")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNoContent());
        Assertions.assertEquals(
                "ACTIVE",
                jdbc.queryForObject(
                        "SELECT tenant_state FROM tenant WHERE tenant_id = 'tenant-north'", String.class));

        mockMvc.perform(get(TenantManagementEndpoint.PATH)
                        .param("q", "north")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants[*].tenantId", hasItem("tenant-north")))
                .andExpect(jsonPath("$.tenants[*].tenantId", not(hasItem("platform"))));
    }

    @Test
    void cannotMutateReservedPlatform() throws Exception {
        mockMvc.perform(post(TenantManagementEndpoint.PATH + "/platform/disable")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(TenantManagementEndpoint.PATH + "/platform")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantName\":\"nope\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disabledTenantRefusesTaskWriteGate() {
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES ('tenant-off', 'Off', 'SUSPENDED')");
        Assertions.assertThrows(
                TenantDisabledException.class, () -> tenantAdmin.requireActiveForTaskWrite("tenant-off"));
        tenantAdmin.requireActiveForTaskWrite("missing-ok-to-auto-create");
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
