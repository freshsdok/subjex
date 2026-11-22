package com.subjex.platform.app.admin;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * SessionListSecurityTest — list is admin.read; revoke is operator.manage.
 * 列表要 admin.read；吊销要 operator.manage。
 */
@WebMvcTest(controllers = SessionListEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    SessionListSecurityTest.GuardConfiguration.class
})
class SessionListSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionListService sessions;

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get(SessionListEndpoint.PATH + "/operators")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(SessionListEndpoint.PATH + "/operators/family-1/revoke"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readerCanListButCannotRevoke() throws Exception {
        when(sessions.listOperators(any())).thenReturn(List.of());
        mockMvc.perform(get(SessionListEndpoint.PATH + "/operators").with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(0));
        mockMvc.perform(post(SessionListEndpoint.PATH + "/operators/family-1/revoke")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCanRevoke() throws Exception {
        when(sessions.revokeOperatorFamily(any(), anyString())).thenReturn(true);
        mockMvc.perform(post(SessionListEndpoint.PATH + "/operators/family-1/revoke")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        verify(sessions).revokeOperatorFamily(any(), anyString());
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
