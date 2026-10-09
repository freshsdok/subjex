package com.subjex.platform.app.security;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OperatorLoginLockoutSecurityTest — Lockout-1：N 次失败 → 429；成功清除；锁定中正确口令仍 429。
 */
@WebMvcTest(controllers = {OperatorAuthEndpoint.class})
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OperatorLoginLockoutSecurityTest.GuardConfiguration.class
})
@TestPropertySource(
        properties = {
            "platform.auth.lockout-max-failures=3",
            "platform.auth.lockout-duration=PT15M"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OperatorLoginLockoutSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void threeFailuresThenTooManyRequestsEvenWithCorrectPassword() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"loginName\":\"" + OPERATOR + "\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + OPERATOR + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.reason", equalTo("login-lockout")));

        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\""
                                + OPERATOR
                                + "\",\"password\":\""
                                + OPERATOR_PASSWORD
                                + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.reason", equalTo("login-lockout")));
    }

    @Test
    void successfulLoginClearsFailures() throws Exception {
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + OPERATOR + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + OPERATOR + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\""
                                + OPERATOR
                                + "\",\"password\":\""
                                + OPERATOR_PASSWORD
                                + "\"}"))
                .andExpect(status().isOk());

        // Two more failures alone must not lock (threshold 3); success cleared the counter.
        // 成功后计数清零；再失败两次（阈=3）仍不应锁定。
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + OPERATOR + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + OPERATOR + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\""
                                + OPERATOR
                                + "\",\"password\":\""
                                + OPERATOR_PASSWORD
                                + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void unknownUserFailuresAlsoLock() throws Exception {
        String unknown = "no-such-operator";
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"loginName\":\"" + unknown + "\",\"password\":\"anything\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + unknown + "\",\"password\":\"anything\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.reason", equalTo("login-lockout")));
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
