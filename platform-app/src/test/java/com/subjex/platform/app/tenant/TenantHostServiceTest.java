package com.subjex.platform.app.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcTenantAdmin;
import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * TenantHostServiceTest — bind unique hosts; resolve Host; fail closed on conflict or disabled tenant.
 * 绑定唯一主机；按 Host 解析；冲突或停用时失败关闭。
 */
class TenantHostServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T00:40:00Z"), ZoneOffset.UTC);
    private static final String TENANT_A = "tenant-host-a";
    private static final String TENANT_B = "tenant-host-b";

    private JdbcTemplate jdbc;
    private TenantHostService service;
    private OperatorPrincipal operator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL));
        jdbc.update("INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, 'A', 'ACTIVE')", TENANT_A);
        jdbc.update("INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, 'B', 'ACTIVE')", TENANT_B);
        service = new TenantHostService(
                jdbc,
                new JdbcTenantAdmin(jdbc),
                new OperatorActionAudit(new JdbcAuditPort(jdbc), CLOCK),
                CLOCK,
                "api.example.com,console.example.com");
        operator = new OperatorPrincipal(
                "host-op", "", LocalOperatorSeeder.IDENTITY_ID, "subject-host", Set.of("tenant.manage"), true);
        mvc = MockMvcBuilders.standaloneSetup(new EchoTenantController(), new PublicTenantHostEndpoint(service))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .addFilters(new TenantHostResolutionFilter(service))
                .build();
    }

    @Test
    void bindsUniqueHostAndRejectsReservedOrInvalid() {
        TenantHostService.Binding bound = service.bind(operator, TENANT_A, "Shop.Acme.COM:443");
        assertThat(bound.hostName()).isEqualTo("shop.acme.com");
        assertThat(service.list(TENANT_A)).hasSize(1);
        assertThat(service.bind(operator, TENANT_A, "shop.acme.com").hostName()).isEqualTo("shop.acme.com");
        assertThatThrownBy(() -> service.bind(operator, TENANT_B, "shop.acme.com"))
                .isInstanceOf(TenantHostAlreadyBoundException.class);
        assertThatThrownBy(() -> service.bind(operator, TENANT_A, "localhost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid host");
        assertThatThrownBy(() -> service.bind(operator, TENANT_A, "api.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
        assertThatThrownBy(() -> service.bind(operator, "platform", "other.acme.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform");
        assertThat(service.unbind(operator, TENANT_A, "shop.acme.com")).isTrue();
        assertThat(service.list(TENANT_A)).isEmpty();
        Integer binds = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE action_name = ? AND outcome = ?",
                Integer.class,
                TenantHostService.AUDIT_BIND,
                AuditOutcome.ALLOWED.name());
        assertThat(binds).isEqualTo(1);
    }

    @Test
    void boundHostFillsTenantHeaderAndConflictsFailClosed() throws Exception {
        service.bind(operator, TENANT_A, "shop.acme.com");
        mvc.perform(get("/api/v1/echo-tenant").header("Host", "shop.acme.com:443"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A));
        mvc.perform(get("/api/v1/echo-tenant")
                        .header("Host", "shop.acme.com")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A));
        mvc.perform(get("/api/v1/echo-tenant")
                        .header("Host", "shop.acme.com")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("Host and X-Tenant-Id do not match"));
    }

    @Test
    void platformAndUnknownHostsDoNotInventATenant() throws Exception {
        service.bind(operator, TENANT_A, "shop.acme.com");
        mvc.perform(get("/api/v1/echo-tenant")
                        .header("Host", "localhost:8080")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_B));
        mvc.perform(get("/api/v1/echo-tenant")
                        .header("Host", "unknown.example.com")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Forwarded-Host", "shop.acme.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A));
        mvc.perform(get("/api/v1/echo-tenant").header("Host", "unknown.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(""));
    }

    @Test
    void suspendedBindingFailsClosed() throws Exception {
        service.bind(operator, TENANT_A, "shop.acme.com");
        new JdbcTenantAdmin(jdbc).disable(TENANT_A);
        mvc.perform(get("/api/v1/echo-tenant").header("Host", "shop.acme.com"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("host tenant is not available"));
        mvc.perform(get(PublicTenantHostEndpoint.PATH).header("Host", "shop.acme.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicDiscoveryUsesRequestHostOnly() throws Exception {
        service.bind(operator, TENANT_A, "shop.acme.com");
        mvc.perform(get(PublicTenantHostEndpoint.PATH).header("Host", "shop.acme.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bound").value(true))
                .andExpect(jsonPath("$.kind").value("BOUND"))
                .andExpect(jsonPath("$.tenantId").value(TENANT_A));
        mvc.perform(get(PublicTenantHostEndpoint.PATH)
                        .header("Host", "localhost")
                        .header("X-Forwarded-Host", "shop.acme.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bound").value(false))
                .andExpect(jsonPath("$.kind").value("PLATFORM"));
        mvc.perform(get(PublicTenantHostEndpoint.PATH).header("Host", "other.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bound").value(false))
                .andExpect(jsonPath("$.kind").value("UNKNOWN"))
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    @RestController
    static class EchoTenantController {
        @GetMapping("/api/v1/echo-tenant")
        Echo echo(@RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
            return new Echo(tenantId == null ? "" : tenantId);
        }

        record Echo(String tenantId) {}
    }
}
