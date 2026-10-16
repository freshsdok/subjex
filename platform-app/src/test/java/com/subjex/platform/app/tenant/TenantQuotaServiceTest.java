package com.subjex.platform.app.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * TenantQuotaServiceTest — TN-1: entity/blob storage count, overrides, 429 + audit REFUSED.
 * 目的：实体/附件计入存储、覆盖上限、超限 429 与审计拒绝。
 */
class TenantQuotaServiceTest {

    private static final String TENANT = "tenant-quota-a";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T04:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private TenantQuotaService quotas;
    private OperatorPrincipal operator;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL));
        ensureTenant(TENANT);
        quotas = new TenantQuotaService(
                jdbc, CLOCK, new OperatorActionAudit(new JdbcAuditPort(jdbc), CLOCK), 100, 3);
        operator = new OperatorPrincipal(
                "quota-op",
                "",
                LocalOperatorSeeder.IDENTITY_ID,
                "subject-quota",
                Set.of("admin.read"),
                true);
    }

    @Test
    void storageCountIncludesEntityRecordAndBlob() {
        insertEntity(TENANT, "e1");
        insertEntity(TENANT, "e2");
        insertBlob(TENANT, "b1");
        assertThat(quotas.countStorageRows(TENANT)).isEqualTo(3);
        assertThat(quotas.usage(TENANT).storageRowCount()).isEqualTo(3);
    }

    @Test
    void storageCapRefusedAndAudited() {
        insertEntity(TENANT, "e1");
        insertEntity(TENANT, "e2");
        insertEntity(TENANT, "e3");
        assertThatThrownBy(() -> quotas.requireWithinQuota(operator, TENANT))
                .isInstanceOf(TenantQuotaExceeded.class)
                .extracting(ex -> ((TenantQuotaExceeded) ex).reason())
                .isEqualTo(TenantQuotaService.REASON_STORAGE_ROWS);
        Integer refused = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM audit_entry
                WHERE action_name = ? AND outcome = ? AND action_target LIKE ?
                """,
                Integer.class,
                TenantQuotaService.AUDIT_QUOTA,
                AuditOutcome.REFUSED.name(),
                TENANT + ":%");
        assertThat(refused).isEqualTo(1);
    }

    @Test
    void perTenantOverrideLowersDailyCap() {
        quotas.putOverride(operator, TENANT, 1, null);
        assertThat(quotas.usage(TENANT).effectiveDailySubmitLimit()).isEqualTo(1);
        assertThat(quotas.usage(TENANT).overrideDailySubmitLimit()).isEqualTo(1);
        assertThat(quotas.usage(TENANT).effectiveStorageRowLimit()).isEqualTo(3);
        insertForm(TENANT, "s1");
        assertThatThrownBy(() -> quotas.requireWithinQuota(operator, TENANT))
                .isInstanceOf(TenantQuotaExceeded.class)
                .extracting(ex -> ((TenantQuotaExceeded) ex).reason())
                .isEqualTo(TenantQuotaService.REASON_DAILY_SUBMIT);
    }

    @Test
    void hitCapMapsTo429WithReason() throws Exception {
        insertEntity(TENANT, "e1");
        insertEntity(TENANT, "e2");
        insertEntity(TENANT, "e3");
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.subjex.platform.app.security.OperatorActionAudit> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new QuotaProbe(quotas))
                .setControllerAdvice(new PlatformExceptionAdvice(provider))
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(operator, null, operator.getAuthorities()));
        try {
            mvc.perform(post("/quota-probe").header("X-Tenant-Id", TENANT))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.reason").value(TenantQuotaService.REASON_STORAGE_ROWS));
        } finally {
            SecurityContextHolder.clearContext();
        }
        Integer refused = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM audit_entry
                WHERE action_name = ? AND outcome = ?
                """,
                Integer.class,
                TenantQuotaService.AUDIT_QUOTA,
                AuditOutcome.REFUSED.name());
        assertThat(refused).isGreaterThanOrEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void migrationCreatesOverrideTable(H2PlatformTables.Mode mode) {
        JdbcTemplate other = new JdbcTemplate(H2PlatformTables.migrated(mode));
        Integer tables = other.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE LOWER(table_name) = 'tenant_quota_override'
                """,
                Integer.class);
        assertThat(tables).isGreaterThanOrEqualTo(1);
    }

    private void ensureTenant(String tenantId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, tenantId);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    tenantId,
                    tenantId);
        }
    }

    private void insertEntity(String tenantId, String recordId) {
        jdbc.update(
                """
                INSERT INTO entity_record
                    (tenant_id, entity_key, record_id, record_state, attrs, created_at, updated_at)
                VALUES (?, 'demo', ?, 'ACTIVE', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                tenantId,
                recordId);
    }

    private void insertBlob(String tenantId, String blobId) {
        jdbc.update(
                """
                INSERT INTO entity_blob
                    (blob_id, tenant_id, entity_key, record_id, field_name, content_type, byte_size,
                     storage_key, checksum_sha256, created_at)
                VALUES (?, ?, 'demo', 'r0', 'file', 'text/plain', 1, ?, 'abc', CURRENT_TIMESTAMP)
                """,
                blobId,
                tenantId,
                "k-" + blobId);
    }

    private void insertForm(String tenantId, String submissionId) {
        // Use service clock so daily window matches (not wall clock).
        // 用服务时钟写入，保证落在日窗口内（不用墙上时钟）。
        jdbc.update(
                """
                INSERT INTO form_submission
                    (submission_id, form_key, actor_identity_id, login_name, values_json, result_summary,
                     submitted_at, tenant_id)
                VALUES (?, 'f', ?, 'quota-op', '{}', 'ok', ?, ?)
                """,
                submissionId,
                LocalOperatorSeeder.IDENTITY_ID,
                java.sql.Timestamp.from(CLOCK.instant()),
                tenantId);
    }

    @RestController
    static class QuotaProbe {
        private final TenantQuotaService quotas;

        QuotaProbe(TenantQuotaService quotas) {
            this.quotas = quotas;
        }

        @PostMapping("/quota-probe")
        void probe(@RequestHeader("X-Tenant-Id") String tenantId) {
            OperatorPrincipal op = (OperatorPrincipal)
                    SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            quotas.requireWithinQuota(op, tenantId);
        }
    }
}
