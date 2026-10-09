package com.subjex.platform.app.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.config.ConfigETags;
import com.subjex.platform.contract.config.ConfigListing;
import com.subjex.platform.contract.config.LocalApplicationConfig;
import com.subjex.platform.contract.config.MemoryConfigOverride;
import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.StaticServiceFallback;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ConfigETagHttpTest — ETag / If-None-Match / If-Match（Config-5c）窄测。
 */
@WebMvcTest(controllers = {ConfigEntriesEndpoint.class, ConfigApiEndpoint.class})
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    ConfigETagHttpTest.CatalogConfiguration.class
})
class ConfigETagHttpTest {

    private static final String DEMO_KEY = "platform.demo.message";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConfigCatalog catalog;

    @BeforeEach
    void seedOverride() {
        catalog.put(DEMO_KEY, "v1");
    }

    @Test
    void getEntriesReturnsEtagAndHonorsIfNoneMatch() throws Exception {
        MvcResult first = mockMvc.perform(get("/config/entries")
                        .param("key", DEMO_KEY)
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andReturn();
        long revision = JSON.readTree(first.getResponse().getContentAsString()).path("revision").asLong();
        String etag = ConfigETags.ofRevision(revision);
        org.junit.jupiter.api.Assertions.assertEquals(etag, first.getResponse().getHeader(ConfigETags.HEADER_ETAG));
        mockMvc.perform(get("/config/entries")
                        .param("key", DEMO_KEY)
                        .header(ConfigETags.HEADER_IF_NONE_MATCH, etag)
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isNotModified());
    }

    @Test
    void putWithStaleIfMatchReturns412() throws Exception {
        long current = currentRevision();
        mockMvc.perform(put("/api/v1/config/" + DEMO_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"v2\"}")
                        .header(ConfigETags.HEADER_IF_MATCH, "\"999999\"")
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(put("/api/v1/config/" + DEMO_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"v2\"}")
                        .header(ConfigETags.HEADER_IF_MATCH, ConfigETags.ofRevision(current))
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(header().string(ConfigETags.HEADER_ETAG, ConfigETags.ofRevision(current + 1)))
                .andExpect(jsonPath("$.revision").value(current + 1));
    }

    @Test
    void postEntriesHonorsIfMatch() throws Exception {
        long current = currentRevision();
        mockMvc.perform(post("/config/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + DEMO_KEY + "\",\"value\":\"x\"}")
                        .header(ConfigETags.HEADER_IF_MATCH, "\"999999\"")
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(post("/config/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + DEMO_KEY + "\",\"value\":\"x\"}")
                        .header(ConfigETags.HEADER_IF_MATCH, ConfigETags.ofRevision(current))
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isNoContent())
                .andExpect(header().string(ConfigETags.HEADER_ETAG, ConfigETags.ofRevision(current + 1)));
    }

    private long currentRevision() throws Exception {
        MvcResult result = mockMvc.perform(get("/config/entries")
                        .param("key", DEMO_KEY)
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return body.path("revision").asLong();
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
            LocalApplicationConfig local = new LocalApplicationConfig(Map.of(
                            hostKey, "127.0.0.1",
                            portKey, "8080",
                            DEMO_KEY, "local")
                    ::get);
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
