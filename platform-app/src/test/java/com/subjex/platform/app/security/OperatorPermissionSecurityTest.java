package com.subjex.platform.app.security;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.admin.AdminReadEndpoint;
import com.subjex.platform.app.admin.AuditPage;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.platform.app.config.ConfigEntriesEndpoint;
import com.subjex.platform.app.discovery.AddressProbe;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.app.discovery.ServiceRegistryEndpoint;
import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.config.ConfigListing;
import com.subjex.platform.contract.config.LocalApplicationConfig;
import com.subjex.platform.contract.config.MemoryConfigOverride;
import com.subjex.platform.contract.discovery.InProcessServiceRegistry;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;


/**
 * OperatorPermissionSecurityTest — purpose: HTTP actions gated by named OperatorPermission.
 * Gates: missing permission -> 403 fail-closed; held permission allows matched routes.
 * <p>
 * 目的：HTTP 动作按具名 OperatorPermission 门禁。门禁：缺权限 -> 403 失败关闭；持有则放行匹配路由。
 */
@WebMvcTest(controllers = {
    ConfigEntriesEndpoint.class, ServiceRegistryEndpoint.class, AdminReadEndpoint.class, AuditPage.class})
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OperatorPermissionSecurityTest.EndpointConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OperatorPermissionSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ApplicationAvailability availability;

    @Test
    void readerCanReadButNotWrite() throws Exception {
        mockMvc.perform(get("/config/entries").param("key", "platform.demo.message")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/registry/services").with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/audit").with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/config/entries").with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"platform.demo.message\",\"value\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/registry/services").with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"sample-consumer\",\"host\":\"127.0.0.1\",\"port\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/tasks").with(httpBasic(VIEWER, VIEWER_PASSWORD)).header("X-Tenant-Id", "tenant-north"))
                .andExpect(status().isForbidden());

        // Refused writes change nothing and leave no audit row.
        // 被拒绝的写入不改任何东西，也不留审计行。
        org.junit.jupiter.api.Assertions.assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_entry", Integer.class));
    }

    @Test
    void wrongPasswordAndAnonymousAreUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/audit")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/audit")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/audit").with(httpBasic(OPERATOR, "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void overrideAndRegisterLeaveReadableAuditEntries() throws Exception {
        mockMvc.perform(post("/config/entries").with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"platform.demo.message\",\"value\":\"hello\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/registry/services").with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"sample-consumer\",\"host\":\"127.0.0.1\",\"port\":1}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/admin/audit").with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.actionName == 'config.override')].actionTarget").value("platform.demo.message"))
                .andExpect(jsonPath("$[?(@.actionName == 'registry.register')].actionTarget").value("sample-consumer"))
                .andExpect(jsonPath("$[0].actorLogin").value(OPERATOR))
                .andExpect(jsonPath("$[0].actorIdentityId").value(LocalOperatorSeeder.IDENTITY_ID))
                .andExpect(jsonPath("$[0].tenantId").value("platform"))
                .andExpect(jsonPath("$[0].outcome").value("ALLOWED"));

        mockMvc.perform(get("/audit").with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("这是一份只读的审计记录")))
                .andExpect(content().string(containsString("覆盖配置")))
                .andExpect(content().string(containsString("register service")))
                .andExpect(content().string(containsString("platform.demo.message")))
                .andExpect(content().string(containsString("<title>审计记录</title>")))
                .andExpect(content().string(containsString(">" + OPERATOR + "<")));
        mockMvc.perform(get("/audit").param("lang", "en").with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(content().string(containsString("<title>Audit entries</title>")));
    }

    @Test
    void auditPageDoesNotAcceptAWrite() throws Exception {
        mockMvc.perform(post("/audit").with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/admin/audit").with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isMethodNotAllowed());
    }

    @TestConfiguration
    static class EndpointConfiguration {
        @Bean
        MemoryConfigOverride memoryConfigOverride() {
            return new MemoryConfigOverride();
        }

        @Bean
        ConfigCatalog configCatalog(MemoryConfigOverride memoryConfigOverride) {
            LocalApplicationConfig local = new LocalApplicationConfig(Map.of("platform.demo.message", "local")::get);
            return new ConfigCatalog(new ConfigListing(memoryConfigOverride, local), memoryConfigOverride);
        }

        @Bean
        ServiceCatalog serviceCatalog() {
            return new ServiceCatalog(new InProcessServiceRegistry(), (host, port) -> AddressProbe.UNKNOWN);
        }

        @Bean
        JdbcAdminReader jdbcAdminReader(JdbcTemplate jdbc) {
            return new JdbcAdminReader(jdbc);
        }

        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
