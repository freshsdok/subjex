package com.subjex.platform.app.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.config.ConfigEntry;
import com.subjex.platform.contract.config.ConfigListing;
import com.subjex.platform.contract.config.ConfigOrigin;
import com.subjex.platform.contract.config.LocalApplicationConfig;
import com.subjex.platform.contract.config.MemoryConfigOverride;
import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.StaticServiceFallback;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {ConfigEntriesEndpoint.class, ConfigListPage.class})
@Import({
    PlatformSecurityConfiguration.class,
    PlatformExceptionAdvice.class,
    ConfigListPageTest.CatalogConfiguration.class
})
class ConfigListPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pageIsOneSentenceAndNamesTheWinningSource() {
        String hostKey = StaticServiceFallback.hostKey(PlatformServiceNames.PLATFORM_APP);
        String portKey = StaticServiceFallback.portKey(PlatformServiceNames.PLATFORM_APP);
        String html = ConfigListPage.html(List.of(
                new ConfigEntry(hostKey, "127.0.0.1", ConfigOrigin.LOCAL),
                new ConfigEntry(portKey, "9090", ConfigOrigin.OVERRIDE),
                new ConfigEntry("<b>", "x", ConfigOrigin.LOCAL)));

        assertTrue(html.contains("这是一份只读的配置名单，只列出键、生效值，以及它来自本地文件还是内存覆盖。"));
        assertTrue(html.contains(
                "This is a read-only config list: a key, the effective value, and whether it came from a local file or a memory override."));
        assertTrue(html.contains("键"));
        assertTrue(html.contains("key"));
        assertTrue(html.contains("生效值"));
        assertTrue(html.contains("effective value"));
        assertTrue(html.contains("来源"));
        assertTrue(html.contains("source"));
        assertTrue(html.contains("本地文件"));
        assertTrue(html.contains("local file"));
        assertTrue(html.contains("内存覆盖"));
        assertTrue(html.contains("memory override"));
        assertTrue(html.contains("9090"));
        assertTrue(html.contains("&lt;b&gt;"));
        assertFalse(html.contains("<form"));
        assertFalse(html.contains("<button"));
        assertFalse(html.contains("namespace"));
        assertFalse(html.contains("history"));
        assertFalse(html.contains("编辑"));
        assertFalse(html.contains("propertySources"));
    }

    @Test
    void listAndPageRequireTheOperator() throws Exception {
        mockMvc.perform(get("/config")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/config/entries").param("key", "platform.demo.message"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/config/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"platform.demo.message\",\"value\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void operatorSeesTheSameWinningSourceOnThePageAndTheEntry() throws Exception {
        String portKey = StaticServiceFallback.portKey(PlatformServiceNames.PLATFORM_APP);
        mockMvc.perform(post("/config/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + portKey + "\",\"value\":\"9090\"}")
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/config/entries")
                        .param("key", portKey)
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(portKey))
                .andExpect(jsonPath("$.value").value("9090"))
                .andExpect(jsonPath("$.source").value("override"));

        mockMvc.perform(get("/config").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("内存覆盖")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("memory override")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("本地文件")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("这是一份只读的配置名单")));
    }

    @Test
    void pageDoesNotAcceptAWrite() throws Exception {
        mockMvc.perform(post("/config").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isMethodNotAllowed());
    }

    @TestConfiguration
    static class CatalogConfiguration {
        @Bean
        MemoryConfigOverride memoryConfigOverride() {
            return new MemoryConfigOverride();
        }

        @Bean
        ConfigListing configListing(MemoryConfigOverride memoryConfigOverride) {
            String hostKey = StaticServiceFallback.hostKey(PlatformServiceNames.PLATFORM_APP);
            String portKey = StaticServiceFallback.portKey(PlatformServiceNames.PLATFORM_APP);
            LocalApplicationConfig local = new LocalApplicationConfig(
                    Map.of(hostKey, "127.0.0.1", portKey, "8080")::get);
            return new ConfigListing(memoryConfigOverride, local);
        }

        @Bean
        ConfigCatalog configCatalog(ConfigListing configListing, MemoryConfigOverride memoryConfigOverride) {
            return new ConfigCatalog(configListing, memoryConfigOverride);
        }

        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
