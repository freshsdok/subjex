package com.subjex.platform.app.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subjex.platform.app.entity.JdbcEntityBlobStore;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcTenantAdmin;
import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * TenantDataLifecycleTest — TN-2 export non-empty ZIP; erase wipes rows + objects + audit.
 * 目的：导出非空 ZIP；硬删除清行与对象并写审计。
 */
class TenantDataLifecycleTest {

    private static final String TENANT = "tenant-life-a";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T05:00:00Z"), ZoneOffset.UTC);

    @TempDir
    java.nio.file.Path storageRoot;

    private JdbcTemplate jdbc;
    private TenantDataLifecycleService lifecycle;
    private OperatorPrincipal operator;
    private ObjectStorage storage;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL));
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, 'Life', 'ACTIVE')",
                TENANT);
        storage = new LocalDirectoryObjectStorage(storageRoot);
        OperatorActionAudit audit = new OperatorActionAudit(new JdbcAuditPort(jdbc), CLOCK);
        lifecycle = new TenantDataLifecycleService(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                new JdbcTenantAdmin(jdbc),
                storage,
                audit,
                new ObjectMapper(),
                CLOCK);
        operator = new OperatorPrincipal(
                "life-op", "", LocalOperatorSeeder.IDENTITY_ID, "subject-life", Set.of("tenant.manage"), true);
        seedTenantData();
    }

    @Test
    void exportZipContainsSeededTablesAndBlobBytes() throws Exception {
        byte[] zip = lifecycle.exportZip(operator, TENANT);
        assertThat(zip.length).isGreaterThan(100);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            boolean sawManifest = false;
            boolean sawTasks = false;
            boolean sawEntities = false;
            boolean sawBlobMeta = false;
            boolean sawBlobBytes = false;
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] body = in.readAllBytes();
                if ("manifest.json".equals(name)) {
                    sawManifest = true;
                    assertThat(new String(body, StandardCharsets.UTF_8)).contains(TENANT);
                }
                if ("tasks.json".equals(name)) {
                    sawTasks = true;
                    assertThat(new String(body, StandardCharsets.UTF_8)).contains("task-life-1");
                }
                if ("entity_records.json".equals(name)) {
                    sawEntities = true;
                    assertThat(new String(body, StandardCharsets.UTF_8)).contains("rec-1");
                }
                if ("entity_blobs.json".equals(name)) {
                    sawBlobMeta = true;
                    assertThat(new String(body, StandardCharsets.UTF_8)).contains("blob-life-1");
                }
                if ("blobs/blob-life-1".equals(name)) {
                    sawBlobBytes = true;
                    assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("hello-blob");
                }
            }
            assertThat(sawManifest).isTrue();
            assertThat(sawTasks).isTrue();
            assertThat(sawEntities).isTrue();
            assertThat(sawBlobMeta).isTrue();
            assertThat(sawBlobBytes).isTrue();
        }
        Integer exports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE action_name = ? AND outcome = ?",
                Integer.class,
                TenantDataLifecycleService.AUDIT_EXPORT,
                AuditOutcome.ALLOWED.name());
        assertThat(exports).isEqualTo(1);
    }

    @Test
    void eraseRequiresSuspendThenWipesRowsAndObjects() {
        assertThatThrownBy(() -> lifecycle.issueEraseChallenge(operator, TENANT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SUSPENDED");

        new JdbcTenantAdmin(jdbc).disable(TENANT);
        var challenge = lifecycle.issueEraseChallenge(operator, TENANT);
        var result = lifecycle.erase(operator, TENANT, challenge.challengeId(), challenge.token());
        assertThat(result.counts().entityRecords()).isEqualTo(1);
        assertThat(result.counts().entityBlobs()).isEqualTo(1);
        assertThat(result.counts().tasks()).isEqualTo(1);
        assertThat(result.counts().forms()).isEqualTo(1);
        assertThat(result.objectStorageDeletes()).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM entity_record WHERE tenant_id = ?", Integer.class, TENANT))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM entity_blob WHERE tenant_id = ?", Integer.class, TENANT))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM platform_task WHERE tenant_id = ?", Integer.class, TENANT))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM form_submission WHERE tenant_id = ?", Integer.class, TENANT))
                .isZero();
        assertThat(storage.find(TENANT, "blob-life-1")).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT tenant_state FROM tenant WHERE tenant_id = ?", String.class, TENANT))
                .isEqualTo("SUSPENDED");

        Integer erases = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE action_name = ? AND outcome = ?",
                Integer.class,
                TenantDataLifecycleService.AUDIT_ERASE,
                AuditOutcome.ALLOWED.name());
        assertThat(erases).isEqualTo(1);
    }

    @Test
    void refusePlatformTenant() {
        assertThatThrownBy(() -> lifecycle.exportZip(operator, "platform"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    private void seedTenantData() {
        jdbc.update(
                """
                INSERT INTO platform_task
                    (task_id, tenant_id, task_kind, task_state, step_name, attempt_count, max_attempt,
                     model_id, input_digest, human_confirmation, failure_reason, created_at)
                VALUES ('task-life-1', ?, 'DETERMINISTIC', 'PENDING', 'start', 0, 3,
                        NULL, NULL, NULL, NULL, ?)
                """,
                TENANT,
                java.sql.Timestamp.from(CLOCK.instant()));
        jdbc.update(
                """
                INSERT INTO form_submission
                    (submission_id, form_key, actor_identity_id, login_name, values_json, result_summary,
                     submitted_at, tenant_id)
                VALUES ('form-life-1', 'f', ?, 'life-op', '{}', 'ok', ?, ?)
                """,
                LocalOperatorSeeder.IDENTITY_ID,
                java.sql.Timestamp.from(CLOCK.instant()),
                TENANT);
        jdbc.update(
                """
                INSERT INTO entity_record
                    (tenant_id, entity_key, record_id, record_state, attrs, created_at, updated_at)
                VALUES (?, 'demo', 'rec-1', 'ACTIVE', '{}', ?, ?)
                """,
                TENANT,
                java.sql.Timestamp.from(CLOCK.instant()),
                java.sql.Timestamp.from(CLOCK.instant()));
        byte[] payload = "hello-blob".getBytes(StandardCharsets.UTF_8);
        storage.put(TENANT, "blob-life-1", payload, "text/plain");
        jdbc.update(
                """
                INSERT INTO entity_blob
                    (blob_id, tenant_id, entity_key, record_id, field_name, content_type, byte_size,
                     storage_key, checksum_sha256, created_at)
                VALUES ('blob-life-1', ?, 'demo', 'rec-1', 'file', 'text/plain', ?, 'blob-life-1', 'x', ?)
                """,
                TENANT,
                payload.length,
                java.sql.Timestamp.from(CLOCK.instant()));
    }
}
