package com.subjex.platform.app.identity.enduser;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * EndUserDirectorySecurityTest — GET /api/v1/end-users needs end-user.read.
 * 终端用户名册读取需要 end-user.read。
 */
@WebMvcTest(controllers = EndUserDirectoryEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    EndUserDirectorySecurityTest.GuardConfiguration.class
})
class EndUserDirectorySecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndUserDirectoryService directory;

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get(EndUserDirectoryEndpoint.PATH).queryParam("tenantId", "tenant-a"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readerCanListDirectory() throws Exception {
        when(directory.list(any(), eq("tenant-a"), isNull(), any()))
                .thenReturn(List.of());
        mockMvc.perform(get(EndUserDirectoryEndpoint.PATH)
                        .queryParam("tenantId", "tenant-a")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(0));
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
