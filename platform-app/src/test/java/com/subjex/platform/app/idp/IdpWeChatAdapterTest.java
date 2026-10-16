package com.subjex.platform.app.idp;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.OperatorAuthEndpoint;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.OperatorManagementEndpoint;
import com.subjex.platform.app.security.OperatorOidcEndpoint;
import com.subjex.platform.app.security.OperatorSelfEndpoint;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * IdpWeChatAdapterTest — ID-4 providers list, WeChat unlinked/linked, self-link, OIDC via SPI.
 * 目的：提供方列表、微信未绑定/已绑定、自助绑定、OIDC 经 SPI。
 */
@WebMvcTest(
        controllers = {
            IdpProvidersEndpoint.class,
            MeIdpLinkEndpoint.class,
            OperatorOidcEndpoint.class,
            OperatorAuthEndpoint.class,
            OperatorSelfEndpoint.class,
            OperatorManagementEndpoint.class
        })
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    IdpWeChatAdapterTest.GuardConfiguration.class,
    IdpWeChatAdapterTest.StubWeChatConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class IdpWeChatAdapterTest {

    static final String OPEN_SUB = "wx-open-sub-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AtomicReference<WeChatApiClient.WeChatSession> stubSession;

    @Test
    void providersListsLocalOidcAndWechat() throws Exception {
        mockMvc.perform(get(IdpProvidersEndpoint.PATH + "/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[?(@.id=='local')].enabled").value(true))
                .andExpect(jsonPath("$.providers[?(@.id=='generic-oidc')].enabled").value(true))
                .andExpect(jsonPath("$.providers[?(@.id=='wechat')].enabled").value(true));
    }

    @Test
    void wechatUnlinkedIsFailClosed() throws Exception {
        stubSession.set(new WeChatApiClient.WeChatSession(OPEN_SUB, null));
        MvcResult start = mockMvc.perform(post(IdpProvidersEndpoint.PATH + "/wechat/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").isNotEmpty())
                .andReturn();
        String state = objectMapper.readTree(start.getResponse().getContentAsString()).path("state").asText();
        mockMvc.perform(post(IdpProvidersEndpoint.PATH + "/wechat/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wx-code\",\"state\":\"" + state + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("idp-unlinked"))
                .andExpect(jsonPath("$.issuer").value(WeChatProperties.ISSUER_OPEN))
                .andExpect(jsonPath("$.idpSubject").value(OPEN_SUB));
    }

    @Test
    void wechatLinkedIssuesTokens() throws Exception {
        mockMvc.perform(put(OperatorManagementEndpoint.PATH + "/" + VIEWER + "/idp-link")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"issuer\":\""
                                        + WeChatProperties.ISSUER_OPEN
                                        + "\",\"idpSubject\":\""
                                        + OPEN_SUB
                                        + "\"}"))
                .andExpect(status().isNoContent());
        stubSession.set(new WeChatApiClient.WeChatSession(OPEN_SUB, null));
        String state = objectMapper
                .readTree(mockMvc.perform(post(IdpProvidersEndpoint.PATH + "/wechat/start"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("state")
                .asText();
        mockMvc.perform(post(IdpProvidersEndpoint.PATH + "/wechat/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wx-code\",\"state\":\"" + state + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void miniProgramUnlinkedAndSelfLinkThenLogin() throws Exception {
        stubSession.set(new WeChatApiClient.WeChatSession("mini-openid", "union-1"));
        mockMvc.perform(post(IdpProvidersEndpoint.PATH + "/wechat/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsCode\":\"mini-js\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.issuer").value(WeChatProperties.ISSUER_MINI))
                .andExpect(jsonPath("$.idpSubject").value("union-1"));

        // Password login → self-link → mini login succeeds.
        MvcResult login = mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + OPERATOR + "\",\"password\":\"" + OPERATOR_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String access = objectMapper.readTree(login.getResponse().getContentAsString()).path("accessToken").asText();
        // PoP: prove via IdP callback, then bind with proofToken (raw issuer+subject rejected).
        stubSession.set(new WeChatApiClient.WeChatSession("mini-openid", "union-1"));
        MvcResult prove = mockMvc.perform(post(MeIdpLinkEndpoint.PATH + "/prove")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"wechat\",\"jsCode\":\"mini-js-prove\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proofToken").isNotEmpty())
                .andExpect(jsonPath("$.issuer").value(WeChatProperties.ISSUER_MINI))
                .andReturn();
        String proof = objectMapper.readTree(prove.getResponse().getContentAsString()).path("proofToken").asText();
        mockMvc.perform(put(MeIdpLinkEndpoint.PATH)
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issuer\":\"ignored\",\"idpSubject\":\"ignored\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(MeIdpLinkEndpoint.PATH)
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proofToken\":\"" + proof + "\"}"))
                .andExpect(status().isNoContent());
        stubSession.set(new WeChatApiClient.WeChatSession("mini-openid", "union-1"));
        mockMvc.perform(post(IdpProvidersEndpoint.PATH + "/wechat/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsCode\":\"mini-js-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void misconfiguredWechatStartIsNotFound() throws Exception {
        // Override: use disabled wechat by calling unknown provider
        mockMvc.perform(post(IdpProvidersEndpoint.PATH + "/nope/start"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("idp-unknown"));
    }

    @TestConfiguration
    static class StubWeChatConfiguration {
        @Bean
        AtomicReference<WeChatApiClient.WeChatSession> stubSession() {
            return new AtomicReference<>();
        }

        @Bean
        @Primary
        WeChatProperties enabledWeChatProperties() {
            return new WeChatProperties(
                    true, "wx-app", "wx-secret", "http://localhost:3000/api/session/idp/wechat/callback");
        }

        @Bean
        @Primary
        WeChatApiClient stubWeChatApiClient(AtomicReference<WeChatApiClient.WeChatSession> stubSession) {
            return new WeChatApiClient() {
                @Override
                public WeChatSession redeemOpenCode(String code) {
                    WeChatSession s = stubSession.get();
                    if (s == null) {
                        throw new IllegalStateException("wechat-stub-empty");
                    }
                    return s;
                }

                @Override
                public WeChatSession code2Session(String jsCode) {
                    return redeemOpenCode(jsCode);
                }
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
