package com.subjex.platform.app.tenant;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.TenantManagementEndpoint;
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
 * TenantQuotaApiSecurityTest — TN-1 GET usage (admin.read) / PUT override (tenant.manage).
 * 目的：用量可读、覆盖需 tenant.manage。
 */
@WebMvcTest(controllers = {TenantQuotaEndpoint.class, TenantManagementEndpoint.class})
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    TenantQuotaApiSecurityTest.GuardConfiguration.class
})
class TenantQuotaApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void viewerCanReadUsageOperatorCanOverride() throws Exception {
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES ('tenant-q', 'Q', 'ACTIVE')");
        String path = TenantManagementEndpoint.PATH + "/tenant-q/quota";
        mockMvc.perform(get(path).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-q"))
                .andExpect(jsonPath("$.globalDailySubmitLimit").isNumber())
                .andExpect(jsonPath("$.effectiveStorageRowLimit").isNumber());

        mockMvc.perform(put(path)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailySubmitLimit\":5,\"storageRowLimit\":50}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put(path)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailySubmitLimit\":5,\"storageRowLimit\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrideDailySubmitLimit").value(5))
                .andExpect(jsonPath("$.effectiveDailySubmitLimit").value(5))
                .andExpect(jsonPath("$.overrideStorageRowLimit").value(50));
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        TenantQuotaService tenantQuotaService(JdbcTemplate jdbc, OperatorActionAudit audit) {
            return new TenantQuotaService(jdbc, Clock.systemUTC(), audit, 10000, 100000);
        }
    }
}
