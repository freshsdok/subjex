package com.subjex.platform.app.form;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.form.render.FormField;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = FormsPage.class)
@Import({
    PlatformSecurityConfiguration.class,
    PlatformExceptionAdvice.class,
    FormCatalog.class,
    FormsPageTest.GateConfiguration.class
})
class FormsPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void yamlFieldNamesAndNotADesignerSentence() throws Exception {
        assertClasspathMatchesFormFile();
        RenderedForm form = FormCatalog.load();
        assertEquals("endpoint-publication", form.formKey());
        assertEquals(
                java.util.List.of("serviceName", "host", "port"),
                form.fields().stream().map(FormField::name).toList());

        String html = FormsPage.html(form);
        assertTrue(html.contains(FormsPage.NOT_A_DESIGNER_ZH));
        assertTrue(html.contains(FormsPage.NOT_A_DESIGNER_EN));
        assertTrue(html.contains("serviceName"));
        assertTrue(html.contains("host"));
        assertTrue(html.contains("port"));
        assertTrue(html.contains("文本"));
        assertTrue(html.contains("整数"));
        assertTrue(html.contains("必填"));
        assertTrue(html.contains("text"));
        assertTrue(html.contains("integer"));
        assertTrue(html.contains("required"));
        assertTrue(html.contains("端点发布"));
        assertTrue(html.contains("Endpoint publication"));
        assertFalse(html.contains("<button"));
        assertFalse(html.contains("<form"));
        assertFalse(html.contains("draggable"));
    }

    @Test
    void pageRequiresTheOperatorAndDoesNotEdit() throws Exception {
        mockMvc.perform(get("/forms")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/forms").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(FormsPage.NOT_A_DESIGNER_EN)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("serviceName")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("host")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("port")));
        mockMvc.perform(post("/forms").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isMethodNotAllowed());
    }

    private static void assertClasspathMatchesFormFile() throws Exception {
        byte[] fromDisk = Files.readAllBytes(formFile());
        try (var in = FormsPageTest.class.getResourceAsStream(FormCatalog.RESOURCE)) {
            assertTrue(in != null, FormCatalog.RESOURCE);
            assertArrayEquals(fromDisk, in.readAllBytes());
        }
        String yaml = new String(fromDisk, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("name: serviceName"));
        assertTrue(yaml.contains("name: host"));
        assertTrue(yaml.contains("name: port"));
    }

    private static Path formFile() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("form-render/src/main/resources/forms/endpoint-publication.form.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("endpoint-publication.form.yaml was not found");
    }

    @TestConfiguration
    static class GateConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
