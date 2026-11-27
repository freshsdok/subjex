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
import com.subjex.platform.app.support.TenantKeyset;
import com.subjex.platform.contract.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
 * TenantLifecycleTest — TN-2 export non-empty ZIP; erase wipes rows + objects + audit.
 * 目的：导出非空 ZIP；硬删除清行与对象并写审计。
 */
class TenantLifecycleTest {

    private static final String TENANT = "tenant-life-a";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T05:00:00Z"), ZoneOffset.UTC);

    @TempDir
    java.nio.file.Path storageRoot;

    private JdbcTemplate jdbc;
    private TenantLifecycleService lifecycle;
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
        lifecycle = new TenantLifecycleService(
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
        byte[] zip = zipBytes(lifecycle, operator, TENANT);
        assertThat(zip.length).isGreaterThan(100);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            boolean sawManifest = false;
            boolean sawTasks = false;
            boolean sawEntities = false;
            boolean sawBlobMeta = false;
            boolean sawBlobBytes = false;
            boolean sawHosts = false;
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
                if ("hosts.json".equals(name)) {
                    sawHosts = true;
                    assertThat(new String(body, StandardCharsets.UTF_8)).contains("life.example.com");
                }
            }
            assertThat(sawManifest).isTrue();
            assertThat(sawTasks).isTrue();
            assertThat(sawEntities).isTrue();
            assertThat(sawBlobMeta).isTrue();
            assertThat(sawBlobBytes).isTrue();
            assertThat(sawHosts).isTrue();
        }
        Integer exports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE action_name = ? AND outcome = ?",
                Integer.class,
                TenantLifecycleService.AUDIT_EXPORT,
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
        assertThat(result.counts().tenantHosts()).isEqualTo(1);
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
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tenant_host WHERE tenant_id = ?", Integer.class, TENANT))
                .isZero();
        assertThat(storage.find(TENANT, "blob-life-1")).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT tenant_state FROM tenant WHERE tenant_id = ?", String.class, TENANT))
                .isEqualTo("SUSPENDED");

        Integer erases = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE action_name = ? AND outcome = ?",
                Integer.class,
                TenantLifecycleService.AUDIT_ERASE,
                AuditOutcome.ALLOWED.name());
        assertThat(erases).isEqualTo(1);
    }

    @Test
    void refusePlatformTenant() {
        assertThatThrownBy(() -> lifecycle.exportZip(operator, "platform", new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
        assertThatThrownBy(() -> lifecycle.requireExportable("platform"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void exportZipStreamsWithoutMaterializingWholeArchive() throws Exception {
        int extraRecords = TenantKeyset.EXPORT_PAGE_LIMIT + 5;
        java.sql.Timestamp at = java.sql.Timestamp.from(CLOCK.instant());
        for (int i = 0; i < extraRecords; i++) {
            jdbc.update(
                    """
                    INSERT INTO entity_record
                        (tenant_id, entity_key, record_id, record_state, attrs, created_at, updated_at)
                    VALUES (?, 'demo', ?, 'ACTIVE', '{}', ?, ?)
                    """,
                    TENANT,
                    "rec-stream-" + i,
                    at,
                    at);
        }
        // Incompressible so the archive itself is large enough to prove chunked writes.
        // 用不可压缩字节，让 ZIP 本身足够大，才能证明是分块写出。
        byte[] payload = new byte[64 * 1024];
        new java.util.Random(42).nextBytes(payload);
        storage.put(TENANT, "blob-stream-1", payload, "application/octet-stream");
        jdbc.update(
                """
                INSERT INTO entity_blob
                    (blob_id, tenant_id, entity_key, record_id, field_name, content_type, byte_size,
                     storage_key, checksum_sha256, created_at)
                VALUES ('blob-stream-1', ?, 'demo', 'rec-1', 'file2', 'application/octet-stream', ?, 'blob-stream-1', 'y', ?)
                """,
                TENANT,
                payload.length,
                at);

        ChunkTrackingOutputStream out = new ChunkTrackingOutputStream();
        lifecycle.exportZip(operator, TENANT, out);
        byte[] zip = out.toByteArray();
        assertThat(zip.length).isGreaterThan(payload.length / 2);
        assertThat(out.maxChunk()).isGreaterThan(0);
        assertThat(out.maxChunk()).isLessThan(out.total());
        boolean sawManifest = false;
        boolean sawLargeBlob = false;
        boolean sawPagedRecord = false;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] body = in.readAllBytes();
                if ("manifest.json".equals(entry.getName())) {
                    sawManifest = true;
                    assertThat(new String(body, StandardCharsets.UTF_8)).contains("subjex-tenant-export-v1");
                }
                if ("entity_records.json".equals(entry.getName())) {
                    String json = new String(body, StandardCharsets.UTF_8);
                    sawPagedRecord = json.contains("rec-stream-0") && json.contains("rec-stream-" + (extraRecords - 1));
                }
                if ("blobs/blob-stream-1".equals(entry.getName())) {
                    sawLargeBlob = true;
                    assertThat(body).isEqualTo(payload);
                }
            }
        }
        assertThat(sawManifest).isTrue();
        assertThat(sawPagedRecord).isTrue();
        assertThat(sawLargeBlob).isTrue();
        Integer exports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE action_name = ? AND outcome = ?",
                Integer.class,
                TenantLifecycleService.AUDIT_EXPORT,
                AuditOutcome.ALLOWED.name());
        assertThat(exports).isEqualTo(1);
    }

    private static byte[] zipBytes(TenantLifecycleService lifecycle, OperatorPrincipal operator, String tenantId)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        lifecycle.exportZip(operator, tenantId, bytes);
        return bytes.toByteArray();
    }

    private static final class ChunkTrackingOutputStream extends OutputStream {
        private final ByteArrayOutputStream all = new ByteArrayOutputStream();
        private int maxChunk;

        @Override
        public void write(int b) {
            maxChunk = Math.max(maxChunk, 1);
            all.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            maxChunk = Math.max(maxChunk, len);
            all.write(b, off, len);
        }

        byte[] toByteArray() {
            return all.toByteArray();
        }

        int maxChunk() {
            return maxChunk;
        }

        int total() {
            return all.size();
        }
    }

    private void seedTenantData() {
        jdbc.update(
                """
                INSERT INTO tenant_host (host_name, tenant_id, created_at, created_by_identity_id)
                VALUES ('life.example.com', ?, ?, ?)
                """,
                TENANT,
                java.sql.Timestamp.from(CLOCK.instant()),
                LocalOperatorSeeder.IDENTITY_ID);
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
