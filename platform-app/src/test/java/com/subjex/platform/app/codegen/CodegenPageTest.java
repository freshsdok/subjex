package com.subjex.platform.app.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.form.render.FormField;
import com.subjex.form.render.FormRenderer;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.form.FormCatalog;
import java.nio.charset.StandardCharsets;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = CodegenPage.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    FormCatalog.class,
    CodegenPageTest.GateConfiguration.class
})
class CodegenPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void htmlNamesTheRecordAndTheFormFields() throws Exception {
        RenderedForm form = publicationForm();
        String html = CodegenPage.html(form);
        assertTrue(html.contains(CodegenPage.NOT_A_BROWSER_GENERATOR_ZH));
        assertTrue(html.contains(CodegenPage.NOT_A_BROWSER_GENERATOR_EN));
        assertTrue(html.contains("EndpointPublication"));
        assertTrue(html.contains("记录"));
        assertTrue(html.contains("record"));
        assertTrue(html.contains("字符串"));
        assertTrue(html.contains("整数"));
        assertTrue(html.contains("String"));
        assertTrue(html.contains("int"));
        for (FormField field : form.fields()) {
            assertTrue(html.contains(field.name()), field.name());
        }
        assertTrue(html.contains("serviceName"));
        assertTrue(html.contains("host"));
        assertTrue(html.contains("port"));
        assertFalse(html.contains("<button"));
        assertFalse(html.contains("<form"));
    }

    @Test
    void pageRequiresTheOperatorAndDoesNotWrite() throws Exception {
        mockMvc.perform(get("/codegen")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/codegen").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EndpointPublication")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(CodegenPage.NOT_A_BROWSER_GENERATOR_EN)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("serviceName")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("host")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("port")));
        mockMvc.perform(post("/codegen").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isMethodNotAllowed());
    }

    private static RenderedForm publicationForm() throws Exception {
        try (var in = CodegenPageTest.class.getResourceAsStream("/forms/endpoint-publication.form.yaml")) {
            if (in == null) {
                throw new IllegalStateException("/forms/endpoint-publication.form.yaml");
            }
            return new FormRenderer().render(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }


    @Test
    void languageAndSkinChangeTheChromeOnly() throws Exception {
        mockMvc.perform(get("/codegen").param("lang", "en").param("skin", "high-contrast")
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<title>Generated type</title>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("lang=\"en\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-skin=\"high-contrast\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("这一页展示从表单生成的类型，不是在浏览器里运行的生成器。")));
        mockMvc.perform(get("/codegen").cookie(new jakarta.servlet.http.Cookie("skin", "calm"))
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-skin=\"calm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<title>生成类型</title>")));
    }

    @TestConfiguration
    static class GateConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
