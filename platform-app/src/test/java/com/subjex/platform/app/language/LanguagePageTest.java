package com.subjex.platform.app.language;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.language.PageLanguage;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import com.subjex.skin.NamedSkin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = LanguagePage.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    LanguagePageTest.GateConfiguration.class
})
class LanguagePageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pageNamesTheCurrentLanguageAndHidesCodes() {
        String chinese = LanguagePage.html(PageLanguage.CHINESE, NamedSkin.PLAIN);
        assertTrue(chinese.contains(LanguagePage.NOT_AN_EDITOR_ZH));
        assertTrue(chinese.contains(LanguagePage.NOT_AN_EDITOR_EN));
        assertTrue(chinese.contains("当前语言"));
        assertTrue(chinese.contains("中文"));
        assertTrue(chinese.contains("服务名单"));
        assertTrue(chinese.contains("配置名单"));
        assertTrue(chinese.contains("部署清单"));
        assertTrue(chinese.contains("字段列表"));
        assertTrue(chinese.contains("生成类型"));
        assertFalse(chinese.contains("title.services"));
        assertFalse(chinese.contains("title.codegen"));
        assertFalse(chinese.contains("Service list"));
        assertFalse(chinese.contains("<form"));
        assertFalse(chinese.contains("<button"));
        assertFalse(chinese.contains("<textarea"));
        assertFalse(chinese.contains("<input"));

        String english = LanguagePage.html(PageLanguage.ENGLISH, NamedSkin.CALM);
        assertTrue(english.contains("current language"));
        assertTrue(english.contains("English"));
        assertTrue(english.contains("Service list"));
        assertTrue(english.contains("Config list"));
        assertTrue(english.contains("Deploy manifests"));
        assertTrue(english.contains("Field list"));
        assertTrue(english.contains("Generated type"));
        assertTrue(english.contains("data-skin=\"calm\""));
        assertTrue(english.contains("lang=\"en\""));
        assertFalse(english.contains("服务名单"));
        assertFalse(english.contains("title.language"));
    }

    @Test
    void queryBeatsTheHeaderAndTheOperatorGateStays() throws Exception {
        mockMvc.perform(get("/language")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/language")
                        .header("Accept-Language", "en-US,en;q=0.8")
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("English")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Service list")));
        mockMvc.perform(get("/language")
                        .param("lang", "zh")
                        .header("Accept-Language", "en")
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("中文")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("服务名单")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Service list"))));
        mockMvc.perform(post("/language").with(httpBasic("platform-operator", "change-me")))
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
