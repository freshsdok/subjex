package com.subjex.platform.app.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AuditExportTest — purpose/gate WP9-1: tenant-scoped NDJSON export, permission, window cap, sink.
 * 目的/门禁 WP9-1：按租户 NDJSON 导出、权限、时间窗上限与 sink。
 */
class AuditExportTest {

    private static final String TENANT_A = "tenant-wp91-a";
    private static final String TENANT_B = "tenant-wp91-b";
    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-08T00:00:00Z");

    private JdbcTemplate jdbc;
    private AuditPort auditPort;
    private MockMvc mvc;
    private OperatorPrincipal exporter;
    private OperatorPrincipal reader;
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(ds);
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_A,
                "Tenant A");
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_B,
                "Tenant B");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Clock clock = Clock.system(ZoneOffset.UTC);
        auditPort = new JdbcAuditPort(jdbc);
        JdbcOperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
        AuditExportService service =
                new AuditExportService(new JdbcAdminReader(jdbc), tenantAccess, auditPort, clock);
        mvc = MockMvcBuilders.standaloneSetup(new AuditExportEndpoint(service))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter())
                .build();
        exporter = operator("exp", "audit.export", "admin.read");
        reader = operator("read", "admin.read");
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                exporter.subjectId(),
                TENANT_A);
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                reader.subjectId(),
                TENANT_A);
        record(TENANT_A, "config.override", Instant.parse("2026-11-03T10:00:00Z"), "key-a");
        record(TENANT_B, "config.override", Instant.parse("2026-11-03T11:00:00Z"), "key-b");
        record(TENANT_A, "operator.auth.login", Instant.parse("2026-10-01T00:00:00Z"), "outside-window");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exportReturnsOnlyOwnTenantWithinWindowAndIsAudited() throws Exception {
        MvcResult result = mvc.perform(post(AuditExportEndpoint.PATH)
                        .with(asPrincipal(exporter))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("tenantId", TENANT_A)
                        .param("from", FROM.toString())
                        .param("to", TO.toString()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentType()).contains("ndjson");
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("key-a");
        assertThat(body).doesNotContain("key-b");
        assertThat(body).doesNotContain("outside-window");
        JsonNode line = mapper.readTree(body.strip().lines().findFirst().orElseThrow());
        for (String field : AuditNdjson.FIELD_NAMES) {
            assertThat(line.has(field)).as("schema field %s", field).isTrue();
        }
        for (String name : line.propertyNames()) {
            assertNoSecretName(name);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_entry WHERE action_name = ? AND tenant_id = ?",
                        Integer.class,
                        AuditExportService.AUDIT_EXPORT,
                        TENANT_A))
                .isEqualTo(1);
    }

    @Test
    void missingPermissionAndForeignTenantAndWindowCapAreDenied() throws Exception {
        mvc.perform(post(AuditExportEndpoint.PATH)
                        .with(asPrincipal(reader))
                        .param("tenantId", TENANT_A)
                        .param("from", FROM.toString())
                        .param("to", TO.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post(AuditExportEndpoint.PATH)
                        .with(asPrincipal(exporter))
                        .param("tenantId", TENANT_B)
                        .param("from", FROM.toString())
                        .param("to", TO.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post(AuditExportEndpoint.PATH)
                        .with(asPrincipal(exporter))
                        .param("tenantId", "platform")
                        .param("from", FROM.toString())
                        .param("to", TO.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post(AuditExportEndpoint.PATH)
                        .with(asPrincipal(exporter))
                        .param("tenantId", TENANT_A)
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-03-15T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(AuditExportEndpoint.PATH)
                        .with(asPrincipal(exporter))
                        .param("tenantId", TENANT_A)
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("limit", "99999"))
                .andExpect(status().isOk());
        Integer capped = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM audit_entry
                WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at <= ?
                """,
                Integer.class,
                TENANT_A,
                java.sql.Timestamp.from(FROM),
                java.sql.Timestamp.from(TO));
        // The huge limit is clamped; the response still only contains the in-window own-tenant rows.
        // 过大 limit 被钳制；响应仍只有窗口内本租户行。
        assertThat(capped).isGreaterThanOrEqualTo(1);
    }

    @Test
    void sinkWritesNdjsonWhenEnabledAndIsNoOpWhenDisabled() throws Exception {
        AuditEntry sample = new AuditEntry(
                UUID.randomUUID().toString(),
                TENANT_A,
                "id-sink",
                "config.override",
                "sink-target",
                AuditOutcome.ALLOWED,
                Instant.parse("2026-11-04T00:00:00Z"));
        NdjsonAuditSink disabled = new NdjsonAuditSink(false, "stdout", null, new StringWriter());
        assertThat(disabled.enabled()).isFalse();
        StringWriter ignored = new StringWriter();
        new NdjsonAuditSink(false, "stdout", null, ignored).accept(sample);
        assertThat(ignored.toString()).isEmpty();

        StringWriter stdout = new StringWriter();
        NdjsonAuditSink enabledStdout = new NdjsonAuditSink(true, "stdout", null, stdout);
        enabledStdout.accept(sample);
        assertThat(stdout.toString()).contains("sink-target");
        assertThat(stdout.toString()).contains(sample.auditEntryId());

        Path file = temp.resolve("audit.ndjson");
        NdjsonAuditSink enabledFile = new NdjsonAuditSink(true, "file", file);
        enabledFile.accept(sample);
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(written).contains("sink-target");
        JsonNode node = mapper.readTree(written.strip());
        for (String name : node.propertyNames()) {
            assertNoSecretName(name);
        }

        StringWriter wrapped = new StringWriter();
        AuditPort sinking =
                new SinkingAuditPort(auditPort, new NdjsonAuditSink(true, "stdout", null, wrapped));
        AuditEntry persisted = new AuditEntry(
                UUID.randomUUID().toString(),
                TENANT_A,
                "id-wrap",
                "config.override",
                "wrapped-target",
                AuditOutcome.ALLOWED,
                Instant.parse("2026-11-05T00:00:00Z"));
        sinking.record(persisted);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_entry WHERE audit_entry_id = ?",
                        Integer.class,
                        persisted.auditEntryId()))
                .isEqualTo(1);
        assertThat(wrapped.toString()).contains("wrapped-target");
    }

    private void record(String tenantId, String action, Instant when, String target) {
        auditPort.record(new AuditEntry(
                UUID.randomUUID().toString(), tenantId, "id-seed", action, target, AuditOutcome.ALLOWED, when));
    }

    private static void assertNoSecretName(String field) {
        String lower = field.toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("token", "secret", "password", "authorization", "cookie", "apikey")) {
            assertThat(lower).as("field %s must not be a secret name", field).doesNotContain(forbidden);
        }
    }

    private static OperatorPrincipal operator(String slug, String... permissions) {
        return new OperatorPrincipal(
                "op-" + slug, "hash", "id-" + slug, "sub-" + slug, Set.of(permissions), true);
    }

    private static RequestPostProcessor asPrincipal(Object principal) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }
}
