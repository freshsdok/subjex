package com.subjex.platform.app.skin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.language.PageLanguage;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import com.subjex.skin.NamedSkin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SkinPage.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    SkinPageTest.GateConfiguration.class
})
class SkinPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pageNamesTheCurrentSkinAndOffersThreeLinks() {
        String html = SkinPage.html(NamedSkin.PLAIN, PageLanguage.CHINESE);
        assertTrue(html.contains(SkinPage.NOT_A_PICKER_ZH));
        assertTrue(html.contains(SkinPage.NOT_A_PICKER_EN));
        assertTrue(html.contains("当前外观"));
        assertTrue(html.contains("朴素"));
        assertTrue(html.contains("高对比"));
        assertTrue(html.contains("沉静"));
        assertTrue(html.contains("plain"));
        assertTrue(html.contains("high contrast"));
        assertTrue(html.contains("calm"));
        assertTrue(html.contains("href=\"/skin?skin=plain\""));
        assertTrue(html.contains("href=\"/skin?skin=high-contrast\""));
        assertTrue(html.contains("href=\"/skin?skin=calm\""));
        assertTrue(html.contains("data-skin=\"plain\""));
        assertFalse(html.contains("<form"));
        assertFalse(html.contains("<button"));
        assertFalse(html.contains("<input"));
        assertFalse(html.contains("type=\"color\""));
    }

    @Test
    void knownLinkSetsACookieAndUnknownDoesNot() throws Exception {
        mockMvc.perform(get("/skin")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/skin").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("朴素")));
        mockMvc.perform(get("/skin").param("skin", "calm").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("skin=calm")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-skin=\"calm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("沉静")));
        mockMvc.perform(get("/skin").param("skin", "neon").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-skin=\"plain\"")));
        mockMvc.perform(post("/skin").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isMethodNotAllowed());
    }

    @TestConfiguration
    static class GateConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
