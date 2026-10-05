package com.subjex.platform.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.app.admin.AuditApiEndpoint;
import com.subjex.platform.app.codegen.CodegenApiEndpoint;
import com.subjex.platform.app.config.ConfigApiEndpoint;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.platform.app.deploy.DeployApiEndpoint;
import com.subjex.platform.app.deploy.ManifestCatalog;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.app.discovery.ServiceListApiEndpoint;
import com.subjex.platform.app.form.FormCatalog;
import com.subjex.platform.app.form.FormsApiEndpoint;
import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.language.LanguageApiEndpoint;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.OperatorSelfEndpoint;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.skin.SkinApiEndpoint;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * OpenApiDocumentTest — OpenAPI 说明文档测试：{@code /api/v1/openapi.json} 需要登录，并且完整描述了 {@code /api/v1}。
 * <p>
 * Proves the description is served only to a signed-in operator (anonymous gets 401), names itself
 * {@code subjex}, declares the HTTP Basic security scheme, and lists every request mapping under
 * {@code /api/v1} that Spring MVC actually registered — so a new JSON endpoint cannot go undocumented.
 * 证明说明文档只给已登录的操作员（匿名得到 401），标题是 {@code subjex}，声明了 HTTP Basic 安全方案，
 * 并且列出了 Spring MVC 实际注册的每一个 {@code /api/v1} 映射——新的 JSON 接口不可能漏写进文档。
 */
@WebMvcTest(controllers = {
    OperatorSelfEndpoint.class,
    ServiceListApiEndpoint.class,
    ConfigApiEndpoint.class,
    AuditApiEndpoint.class,
    DeployApiEndpoint.class,
    FormsApiEndpoint.class,
    CodegenApiEndpoint.class,
    LanguageApiEndpoint.class,
    SkinApiEndpoint.class
})
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    FormCatalog.class,
    ManifestCatalog.class,
    JsonApi.class,
    SpringDocConfiguration.class,
    SpringDocConfigProperties.class,
    SpringDocWebMvcConfiguration.class,
    OpenApiDocumentTest.SliceConfiguration.class
})
class OpenApiDocumentTest {

    private static final String DOCUMENT = JsonApi.BASE + "/openapi.json";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    private ServiceCatalog serviceCatalog;

    @MockitoBean
    private ConfigCatalog configCatalog;

    @Test
    void anonymousIsRefused() throws Exception {
        mockMvc.perform(get(DOCUMENT)).andExpect(status().isUnauthorized());
    }

    @Test
    void describesEveryApiMappingBehindHttpBasic() throws Exception {
        String body = mockMvc.perform(get(DOCUMENT).with(httpBasic(
                        OperatorDirectoryTestConfiguration.OPERATOR,
                        OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode document = objectMapper.readTree(body);

        assertThat(document.path("openapi").asText()).startsWith("3.");
        assertThat(document.path("info").path("title").asText()).isEqualTo("subjex");
        JsonNode scheme = document.path("components").path("securitySchemes").path(JsonApi.BASIC_SCHEME);
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("basic");

        Set<String> mapped = new TreeSet<>();
        handlerMapping.getHandlerMethods().keySet().forEach(mapping ->
                mapping.getPatternValues().stream()
                        .filter(pattern -> pattern.startsWith(JsonApi.BASE))
                        .filter(pattern -> !pattern.startsWith(DOCUMENT))
                        .forEach(mapped::add));
        assertThat(mapped).contains(JsonApi.BASE + "/me", JsonApi.BASE + "/config/{key}");

        Set<String> documented = new TreeSet<>();
        document.path("paths").fieldNames().forEachRemaining(documented::add);
        assertThat(documented).containsAll(mapped);

        JsonNode override = document.path("paths").path(JsonApi.BASE + "/config/{key}").path("put").path("responses");
        assertThat(override.has("401")).isTrue();
        assertThat(override.has("403")).isTrue();
    }

    @TestConfiguration
    static class SliceConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        JdbcAdminReader jdbcAdminReader(JdbcTemplate jdbc) {
            return new JdbcAdminReader(jdbc);
        }
    }
}
