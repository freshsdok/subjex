package com.subjex.platform.app.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.entity.ChunkedBlobUploadSession;
import com.subjex.platform.app.entity.EntityBlobMetadata;
import com.subjex.platform.app.entity.JdbcEntityBlobStore;
import com.subjex.platform.app.entity.LocalDirChunkedBlobUploadStore;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.inbox.InboxAttachmentBind;
import com.subjex.platform.contract.storage.StoredObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ObjectUploadCapTest — WP3-3 generic single-file cap of at least 50 MiB (ADR 0015 D4).
 * 目的：默认上限 ≥ 50 MiB；同一 CHUNK-1 组装后 put 路径在下调上限下可完成、超限拒绝；
 * 完成后登记所有者，无授权主体拿不到下载链接；租户隔离仍成立。不分配完整 50 MiB。
 */
class ObjectUploadCapTest {

    private static final Instant NOW = Instant.parse("2026-11-17T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String OWNER = "subject-owner";
    private static final String OTHER = "subject-other";
    private static final int TEST_CAP = 8 * 1024;
    private static final int CHUNK = 2 * 1024;

    @Test
    void defaultAndContractCapsAreAtLeastFiftyMebibytes() {
        assertTrue(ObjectUploadLimits.DEFAULT_MAX_BYTES >= 50 * 1024 * 1024);
        assertEquals(50 * 1024 * 1024, ObjectUploadLimits.DEFAULT_MAX_BYTES);
        assertEquals(ObjectUploadLimits.DEFAULT_MAX_BYTES, JdbcEntityBlobStore.MAX_BYTES);
        assertEquals(ObjectUploadLimits.DEFAULT_MAX_BYTES, InboxAttachmentBind.MAX_BYTES);
        assertEquals(ObjectUploadLimits.DEFAULT_MAX_BYTES, ObjectUploadLimits.parseMaxBytes("50MB", 1));
        assertEquals(ObjectUploadLimits.DEFAULT_MAX_BYTES, ObjectUploadLimits.parseMaxBytes("50MiB", 1));
        assertEquals(
                ObjectUploadLimits.DEFAULT_MAX_BYTES,
                ObjectUploadLimits.parseMaxBytes(Integer.toString(50 * 1024 * 1024), 1));
        // Yaml default must stay at 50MB — 配置默认必须仍是 50MB。
        String yaml = readApplicationYaml();
        assertTrue(yaml.contains("max-bytes: ${PLATFORM_STORAGE_UPLOAD_MAX_BYTES:50MB}"));
        assertTrue(yaml.contains("max-file-size: ${SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE:${PLATFORM_STORAGE_UPLOAD_MAX_BYTES:50MB}}"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void chunkedUploadJustUnderLoweredCapIsAssembledPutAndRegistered(
            H2PlatformTables.Mode mode, @TempDir Path root) throws Exception {
        Harness h = Harness.open(mode, root);
        byte[] payload = new byte[TEST_CAP - 16];
        Arrays.fill(payload, (byte) 0x5A);

        ChunkedBlobUploadSession session = h.chunks.init(
                TENANT_A, "hybrid-asset", "r-1", "attachment", "application/octet-stream", payload.length, TEST_CAP);
        int count = session.chunkCount();
        assertTrue(count > 1);
        for (int i = 0; i < count; i++) {
            int from = i * CHUNK;
            int to = Math.min(from + CHUNK, payload.length);
            h.chunks.putChunk(session.uploadId(), i, Arrays.copyOfRange(payload, from, to));
        }
        byte[] assembled = h.chunks.assemble(session.uploadId());
        assertArrayEquals(payload, assembled);

        EntityBlobMetadata meta = h.blobs.put(
                TENANT_A, "hybrid-asset", "r-1", "attachment", assembled, "application/octet-stream");
        h.grants.register(TENANT_A, meta.storageKey(), OWNER);
        h.chunks.abort(session.uploadId());

        Optional<StoredObject> stored = h.objects.find(TENANT_A, meta.storageKey());
        assertTrue(stored.isPresent());
        assertArrayEquals(payload, stored.get().content());
        assertTrue(h.objects.find(TENANT_B, meta.storageKey()).isEmpty());

        assertEquals(
                ObjectAccessDecision.ALLOW, h.authorizer.decide(TENANT_A, meta.storageKey(), OWNER));
        assertEquals(
                ObjectAccessDecision.DENY, h.authorizer.decide(TENANT_A, meta.storageKey(), OTHER));
        assertEquals(
                ObjectAccessDecision.DENY, h.authorizer.decide(TENANT_B, meta.storageKey(), OWNER));

        IssuedDownloadLink ownerLink = h.downloads.issue(TENANT_A, meta.storageKey(), OWNER, null);
        assertTrue(ownerLink.url() != null && !ownerLink.url().isBlank());
        ObjectDownloadDeniedException denied = assertThrows(
                ObjectDownloadDeniedException.class,
                () -> h.downloads.issue(TENANT_A, meta.storageKey(), OTHER, Duration.ofMinutes(5)));
        assertEquals(ObjectDownloadDeniedException.REASON, denied.getMessage());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void overCapIsRejectedOnInitAndOnPut(H2PlatformTables.Mode mode, @TempDir Path root) {
        Harness h = Harness.open(mode, root);
        IllegalArgumentException initDenied = assertThrows(
                IllegalArgumentException.class,
                () -> h.chunks.init(
                        TENANT_A,
                        "hybrid-asset",
                        "r-1",
                        "attachment",
                        "application/octet-stream",
                        TEST_CAP + 1,
                        TEST_CAP));
        assertTrue(initDenied.getMessage().contains(Integer.toString(TEST_CAP)));

        byte[] huge = new byte[TEST_CAP + 1];
        IllegalArgumentException putDenied = assertThrows(
                IllegalArgumentException.class,
                () -> h.blobs.put(TENANT_A, "hybrid-asset", "r-1", "attachment", huge, "application/octet-stream"));
        assertTrue(putDenied.getMessage().contains("maxBytes=" + TEST_CAP));
        assertEquals(0, h.countDescriptors());
    }

    private static String readApplicationYaml() {
        try {
            Path yaml = Path.of("src/main/resources/application.yml");
            if (!Files.isRegularFile(yaml)) {
                yaml = Path.of("platform-app/src/main/resources/application.yml");
            }
            return Files.readString(yaml);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot read application.yml", ex);
        }
    }

    private static final class Harness {
        final JdbcTemplate jdbc;
        final LocalDirectoryObjectStorage objects;
        final JdbcObjectGrantStore grants;
        final ObjectAccessAuthorizer authorizer;
        final JdbcEntityBlobStore blobs;
        final LocalDirChunkedBlobUploadStore chunks;
        final ObjectDownloadLinkService downloads;

        private Harness(JdbcTemplate jdbc, Path root) {
            this.jdbc = jdbc;
            this.objects = new LocalDirectoryObjectStorage(root.resolve("objects"));
            this.grants = new JdbcObjectGrantStore(jdbc, CLOCK);
            this.authorizer = new ObjectAccessAuthorizer(grants, jdbc, CLOCK);
            ObjectUploadLimits limits = new ObjectUploadLimits(TEST_CAP);
            this.blobs = new JdbcEntityBlobStore(jdbc, objects, CLOCK, limits);
            this.chunks = new LocalDirChunkedBlobUploadStore(root.resolve("chunks"), CLOCK, CHUNK);
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
                    (tenantId, objectKey, actor, expiresAt) -> {});
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

        int countDescriptors() {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM object_descriptor", Integer.class);
            return n == null ? 0 : n;
        }
    }
}
