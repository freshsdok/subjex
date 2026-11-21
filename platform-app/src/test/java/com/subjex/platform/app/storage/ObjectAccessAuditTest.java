package com.subjex.platform.app.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.admin.AuditQuery;
import com.subjex.platform.app.admin.AuditRow;
import com.subjex.platform.app.entity.AuthorizedObjectRegistrar;
import com.subjex.platform.app.entity.EntityBlobMetadata;
import com.subjex.platform.app.entity.JdbcEntityBlobStore;
import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ObjectAccessAuditTest — WP3-4 AuditPort events for upload, download issue, and grant changes.
 * 目的：上传/签发/授权变更写入既有审计端口；非白名单 MIME 拒绝且不落盘；可按租户经既有查询路径读出。
 */
class ObjectAccessAuditTest {

    private static final Instant NOW = Instant.parse("2026-11-17T02:10:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String OWNER = "subject-owner";
    private static final String OTHER = "subject-other";
    private static final String ACTOR = "identity-owner";

    @Test
    void sharedAllowlistStartsFromAdr0009AndRejectsUnknownTypes() {
        assertTrue(ObjectContentTypes.isAllowed("text/plain"));
        assertTrue(ObjectContentTypes.isAllowed("application/pdf"));
        assertTrue(ObjectContentTypes.isAllowed("image/png"));
        assertTrue(ObjectContentTypes.isAllowed("image/jpeg"));
        assertTrue(ObjectContentTypes.isAllowed("image/webp"));
        assertTrue(ObjectContentTypes.isAllowed(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertTrue(InboxAliasDelegates());
        assertTrue(!ObjectContentTypes.isAllowed("application/octet-stream"));
        assertTrue(!ObjectContentTypes.isAllowed("application/dicom"));
        assertTrue(!ObjectContentTypes.isAllowed("application/zip"));
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> ObjectContentTypes.requireAllowed("application/zip"));
        assertTrue(ex.getMessage().contains("contentType not allowed"));
    }

    private static boolean InboxAliasDelegates() {
        return com.subjex.platform.app.inbox.InboxAttachmentContentTypes.isAllowed("text/plain")
                && com.subjex.platform.app.inbox.InboxAttachmentContentTypes.allowed()
                        .equals(ObjectContentTypes.allowed());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void uploadDownloadAndGrantEventsAreQueryableByTenant(H2PlatformTables.Mode mode, @TempDir Path root) {
        Harness h = Harness.open(mode, root);
        byte[] body = "hello-audit".getBytes(StandardCharsets.UTF_8);

        EntityBlobMetadata meta = h.registrar.putRegistered(
                TENANT_A, "hybrid-asset", "r-1", "attachment", body, "text/plain", OWNER, ACTOR);
        assertEquals("text/plain", meta.contentType());
        assertTrue(h.objects.find(TENANT_A, meta.storageKey()).isPresent());

        IssuedDownloadLink link = h.downloads.issue(TENANT_A, meta.storageKey(), OWNER, Duration.ofMinutes(5));
        assertTrue(link.url() != null && !link.url().isBlank());

        ObjectGrant grant = h.grants.grantToSubject(TENANT_A, meta.storageKey(), OTHER, null, OWNER);
        h.grants.revoke(grant.grantId(), OWNER);

        List<AuditRow> uploads = h.query(TENANT_A, ObjectAccessAudit.UPLOAD);
        assertEquals(1, uploads.size());
        assertEquals(TENANT_A, uploads.get(0).tenantId());
        assertEquals(ACTOR, uploads.get(0).actorIdentityId());
        assertEquals(meta.storageKey(), uploads.get(0).actionTarget());
        assertEquals(AuditOutcome.ALLOWED.name(), uploads.get(0).outcome());
        assertEquals("hybrid-asset", uploads.get(0).entityKey());

        List<AuditRow> issues = h.query(TENANT_A, ObjectAccessAudit.DOWNLOAD_ISSUE);
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).actionTarget().startsWith(meta.storageKey()));
        assertEquals(AuditOutcome.ALLOWED.name(), issues.get(0).outcome());

        List<AuditRow> creates = h.query(TENANT_A, ObjectAccessAudit.GRANT_CREATE);
        assertEquals(1, creates.size());
        assertTrue(creates.get(0).actionTarget().contains(OTHER));
        assertEquals(AuditOutcome.ALLOWED.name(), creates.get(0).outcome());

        List<AuditRow> revokes = h.query(TENANT_A, ObjectAccessAudit.GRANT_REVOKE);
        assertEquals(1, revokes.size());
        assertTrue(revokes.get(0).actionTarget().contains(grant.grantId()));

        assertTrue(h.query(TENANT_B, ObjectAccessAudit.UPLOAD).isEmpty());
        assertTrue(h.query(TENANT_B, ObjectAccessAudit.DOWNLOAD_ISSUE).isEmpty());
        assertTrue(h.query(TENANT_B, ObjectAccessAudit.GRANT_CREATE).isEmpty());
        assertTrue(h.query(TENANT_B, ObjectAccessAudit.GRANT_REVOKE).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void nonWhitelistedMimeIsRejectedAndNotStored(H2PlatformTables.Mode mode, @TempDir Path root) {
        Harness h = Harness.open(mode, root);
        byte[] body = "not-allowed".getBytes(StandardCharsets.UTF_8);
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> h.registrar.putRegistered(
                        TENANT_A, "hybrid-asset", "r-1", "attachment", body, "application/zip", OWNER, ACTOR));
        assertTrue(ex.getMessage().contains("contentType not allowed"));
        assertEquals(0, h.countBlobs());
        assertEquals(0, h.countDescriptors());
        assertTrue(h.objects.find(TENANT_A, "any").isEmpty());

        List<AuditRow> refused = h.query(TENANT_A, ObjectAccessAudit.UPLOAD);
        assertEquals(1, refused.size());
        assertEquals(AuditOutcome.REFUSED.name(), refused.get(0).outcome());
        assertTrue(refused.get(0).actionTarget().contains("application/zip"));
        assertTrue(h.query(TENANT_B, ObjectAccessAudit.UPLOAD).isEmpty());
    }

    private static final class Harness {
        final JdbcTemplate jdbc;
        final LocalDirectoryObjectStorage objects;
        final JdbcObjectGrantStore grants;
        final AuthorizedObjectRegistrar registrar;
        final ObjectDownloadLinkService downloads;
        final JdbcAdminReader reader;

        private Harness(JdbcTemplate jdbc, Path root) {
            this.jdbc = jdbc;
            this.objects = new LocalDirectoryObjectStorage(root.resolve("objects"));
            ObjectAccessAudit audit = new ObjectAccessAudit(new JdbcAuditPort(jdbc), CLOCK);
            this.grants = new JdbcObjectGrantStore(jdbc, CLOCK, audit);
            JdbcEntityBlobStore blobs = new JdbcEntityBlobStore(jdbc, objects, CLOCK);
            this.registrar = new AuthorizedObjectRegistrar(blobs, grants, audit);
            ObjectAccessAuthorizer authorizer = new ObjectAccessAuthorizer(grants, jdbc, CLOCK);
            LocalDownloadSignature signature = new LocalDownloadSignature("test-hmac-key");
            ObjectDownloadProperties properties = new ObjectDownloadProperties(
                    ObjectDownloadProperties.DEFAULT_TTL,
                    ObjectDownloadProperties.HARD_CAP,
                    "http://127.0.0.1:8080",
                    "test-hmac-key");
            this.downloads = new ObjectDownloadLinkService(
                    authorizer,
                    new LocalHmacDownloadIssuer(signature, properties.publicBaseUrl(), CLOCK),
                    signature,
                    objects,
                    properties,
                    CLOCK,
                    audit);
            this.reader = new JdbcAdminReader(jdbc);
        }

        static Harness open(H2PlatformTables.Mode mode, Path root) {
            DataSource ds = H2PlatformTables.migrated(mode);
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT_A,
                    "Tenant A");
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT_B,
                    "Tenant B");
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?, ?, 'PERSON')",
                    OWNER,
                    OWNER);
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?, ?, 'PERSON')",
                    OTHER,
                    OTHER);
            return new Harness(jdbc, root);
        }

        List<AuditRow> query(String tenantId, String action) {
            return reader.auditEntries(new AuditQuery(tenantId, null, action, null, null, 50));
        }

        int countBlobs() {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM entity_blob", Integer.class);
            return n == null ? 0 : n;
        }

        int countDescriptors() {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM object_descriptor", Integer.class);
            return n == null ? 0 : n;
        }
    }
}
