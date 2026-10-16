package com.subjex.platform.app.tenant;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.TenantManagementEndpoint;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.nio.file.Files;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * TenantLifecycleApiSecurityTest — TN-2 export/erase require tenant.manage.
 * 目的：导出/擦除需 tenant.manage。
 */
@WebMvcTest(controllers = {TenantLifecycleEndpoint.class, TenantManagementEndpoint.class})
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    TenantLifecycleApiSecurityTest.Slice.class
})
class TenantLifecycleApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void viewerForbiddenOperatorCanExport() throws Exception {
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES ('tenant-ex', 'Ex', 'ACTIVE')");
        String path = TenantManagementEndpoint.PATH + "/tenant-ex/export";
        mockMvc.perform(post(path).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path).with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"));
    }

    @TestConfiguration
    static class Slice {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        ObjectStorage objectStorage() throws Exception {
            return new LocalDirectoryObjectStorage(Files.createTempDirectory("tn2-api-storage"));
        }

        @Bean
        TenantLifecycleService tenantLifecycleService(
                JdbcTemplate jdbc,
                OperatorActionAudit audit,
                ObjectStorage objectStorage,
                com.subjex.platform.app.security.JdbcTenantAdmin tenants) {
            return new TenantLifecycleService(
                    jdbc,
                    new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                    tenants,
                    objectStorage,
                    audit,
                    new ObjectMapper(),
                    Clock.systemUTC());
        }
    }
}
