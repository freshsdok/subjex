package com.subjex.platform.app.tenant;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.JdbcTenantAdmin;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TenantHostApiSecurityTest — list is admin.read; bind/unbind is tenant.manage; discovery is public.
 * 列表要 admin.read；绑定/解除要 tenant.manage；发现接口公开可读。
 */
@WebMvcTest(controllers = {TenantHostEndpoint.class, PublicTenantHostEndpoint.class})
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    TenantHostApiSecurityTest.GuardConfiguration.class
})
class TenantHostApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void viewerCanListOperatorCanBindAndAnonymousCanDiscover() throws Exception {
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES ('tenant-host-sec', 'Sec', 'ACTIVE')");
        String path = TenantHostEndpoint.PATH.replace("{tenantId}", "tenant-host-sec");
        mockMvc.perform(get(path).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hosts.length()").value(0));

        mockMvc.perform(post(path)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostName\":\"shop.acme.com\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(path)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostName\":\"Shop.Acme.COM\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hostName").value("shop.acme.com"));

        mockMvc.perform(get(path).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hosts[0].hostName").value("shop.acme.com"));

        mockMvc.perform(get(PublicTenantHostEndpoint.PATH).header("Host", "shop.acme.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bound").value(true))
                .andExpect(jsonPath("$.tenantId").value("tenant-host-sec"));

        mockMvc.perform(delete(path + "/shop.acme.com").with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(path + "/shop.acme.com").with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNoContent());
    }

    @Test
    void unauthenticatedManageIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/tenant-host-sec/hosts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/tenants/tenant-host-sec/hosts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostName\":\"shop.acme.com\"}"))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        TenantHostService tenantHostService(
                JdbcTemplate jdbc, JdbcTenantAdmin tenants, OperatorActionAudit audit) {
            return new TenantHostService(jdbc, tenants, audit, Clock.systemUTC(), "localhost,api.example.com");
        }
    }
}
