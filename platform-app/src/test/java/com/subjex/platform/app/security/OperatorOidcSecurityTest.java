package com.subjex.platform.app.security;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


/**
 * OperatorOidcSecurityTest — purpose: Slice E OIDC callback issues platform tokens.
 * Gates: unlinked IdP subject denied (fail-closed); admin bind/unbind; linked login succeeds.
 * <p>
 * 目的：Slice E OIDC 回调签发平台令牌。门禁：未绑定 IdP 主体失败关闭拒绝；管理员绑定/解绑；已绑定可登录。
 */
@WebMvcTest(
        controllers = {
            OperatorOidcEndpoint.class,
            OperatorAuthEndpoint.class,
            OperatorSelfEndpoint.class,
            OperatorManagementEndpoint.class
        })
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OperatorOidcSecurityTest.GuardConfiguration.class,
    OperatorOidcSecurityTest.StubOidcConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OperatorOidcSecurityTest {

    static final String ISSUER = "https://idp.example/realms/subjex";
    static final String IDP_SUB = "idp-user-viewer-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AtomicReference<OidcClaims> stubClaims;

    @Test
    void statusReportsEnabledWhenConfigured() throws Exception {
        mockMvc.perform(get(OperatorOidcEndpoint.PATH + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void unlinkedIdpSubjectIsRefusedWithoutAutoProvision() throws Exception {
        stubClaims.set(new OidcClaims(ISSUER, IDP_SUB, "viewer@example.com"));
        MvcResult start = mockMvc.perform(post(OperatorOidcEndpoint.PATH + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").isNotEmpty())
                .andExpect(jsonPath("$.state").isNotEmpty())
                .andReturn();
        String state = objectMapper.readTree(start.getResponse().getContentAsString()).path("state").asText();

        mockMvc.perform(post(OperatorOidcEndpoint.PATH + "/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"auth-code\",\"state\":\"" + state + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("oidc-unlinked"))
                .andExpect(jsonPath("$.idpSubject").value(IDP_SUB));

        Integer accounts = jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE login_name = ?", Integer.class, IDP_SUB);
        assertThat(accounts).isZero();
        Integer links = jdbc.queryForObject("SELECT COUNT(*) FROM operator_idp_link", Integer.class);
        assertThat(links).isZero();
    }

    @Test
    void linkedOperatorGetsBearerTokensAndSkipsLocalTotp() throws Exception {
        mockMvc.perform(put(OperatorManagementEndpoint.PATH + "/" + VIEWER + "/idp-link")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issuer\":\"" + ISSUER + "\",\"idpSubject\":\"" + IDP_SUB + "\"}"))
                .andExpect(status().isNoContent());

        stubClaims.set(new OidcClaims(ISSUER, IDP_SUB, "viewer@example.com"));
        MvcResult start = mockMvc.perform(post(OperatorOidcEndpoint.PATH + "/start"))
                .andExpect(status().isOk())
                .andReturn();
        String state = objectMapper.readTree(start.getResponse().getContentAsString()).path("state").asText();

        MvcResult tokens = mockMvc.perform(post(OperatorOidcEndpoint.PATH + "/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"auth-code\",\"state\":\"" + state + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.mfaRequired").doesNotExist())
                .andReturn();
        String access = objectMapper.readTree(tokens.getResponse().getContentAsString()).path("accessToken").asText();
        mockMvc.perform(get(OperatorSelfEndpoint.PATH).header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginName").value(VIEWER));
    }

    @Test
    void adminCanUnlinkIdpBinding() throws Exception {
        mockMvc.perform(put(OperatorManagementEndpoint.PATH + "/" + VIEWER + "/idp-link")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issuer\":\"" + ISSUER + "\",\"idpSubject\":\"" + IDP_SUB + "\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(OperatorManagementEndpoint.PATH + "/" + VIEWER + "/idp-link")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.idpSubject").value(IDP_SUB));

        mockMvc.perform(delete(OperatorManagementEndpoint.PATH + "/" + VIEWER + "/idp-link")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(OperatorManagementEndpoint.PATH + "/" + VIEWER + "/idp-link")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false));
    }

    @Test
    void reusedStateIsRejected() throws Exception {
        mockMvc.perform(put(OperatorManagementEndpoint.PATH + "/" + VIEWER + "/idp-link")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issuer\":\"" + ISSUER + "\",\"idpSubject\":\"" + IDP_SUB + "\"}"))
                .andExpect(status().isNoContent());
        stubClaims.set(new OidcClaims(ISSUER, IDP_SUB, null));
        String state = objectMapper
                .readTree(mockMvc.perform(post(OperatorOidcEndpoint.PATH + "/start"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("state")
                .asText();
        mockMvc.perform(post(OperatorOidcEndpoint.PATH + "/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"auth-code\",\"state\":\"" + state + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(OperatorOidcEndpoint.PATH + "/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"auth-code\",\"state\":\"" + state + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class StubOidcConfiguration {
        @Bean
        AtomicReference<OidcClaims> stubClaims() {
            return new AtomicReference<>();
        }

        @Bean
        @Primary
        OidcTokenClient stubOidcTokenClient(AtomicReference<OidcClaims> stubClaims) {
            return (pending, code) -> {
                OidcClaims claims = stubClaims.get();
                if (claims == null) {
                    throw new InvalidOperatorTokenException("oidc-stub-empty");
                }
                return claims;
            };
        }
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
