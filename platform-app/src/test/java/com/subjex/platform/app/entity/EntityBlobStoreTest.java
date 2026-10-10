package com.subjex.platform.app.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;


/**
 * EntityBlobStoreTest — purpose: ES-2 attachment metadata + ObjectStorage; keep bytes out of attrs.
 * Gates: tenant isolation; checksum; delete cascade; reject oversize; platform tenant mapping.
 * <p>
 * 目的：ES-2 附件元数据 + ObjectStorage；字节不进 attrs。门禁：租户隔离；校验和；级联删除；超限拒绝。
 */
class EntityBlobStoreTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-10T16:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path objectRoot;

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void putFindListAndDelete(H2PlatformTables.Mode mode) {
        EntityBlobStore store = newStore(mode);
        byte[] body = "hello-attachment".getBytes(StandardCharsets.UTF_8);

        EntityBlobMetadata meta =
                store.put("tenant-a", "hybrid-asset", "r-1", "bodyBlob", body, "text/plain");
        assertEquals("tenant-a", meta.tenantId());
        assertEquals("hybrid-asset", meta.entityKey());
        assertEquals("r-1", meta.recordId());
        assertEquals("bodyBlob", meta.fieldName());
        assertEquals(body.length, meta.byteSize());
        assertFalse(meta.checksumSha256().isBlank());

        assertArrayEquals(body, store.findContent("tenant-a", meta.blobId()).orElseThrow());
        assertTrue(store.findContent("tenant-b", meta.blobId()).isEmpty());
        assertTrue(store.findMetadata("tenant-b", meta.blobId()).isEmpty());

        List<EntityBlobMetadata> listed = store.listForRecord("tenant-a", "hybrid-asset", "r-1");
        assertEquals(1, listed.size());
        assertEquals(meta.blobId(), listed.get(0).blobId());

        assertTrue(store.delete("tenant-a", meta.blobId()));
        assertTrue(store.findMetadata("tenant-a", meta.blobId()).isEmpty());
        assertTrue(store.findContent("tenant-a", meta.blobId()).isEmpty());
        assertFalse(store.delete("tenant-a", meta.blobId()));
    }

    @Test
    void platformTenantMapsForObjectStorage() {
        EntityBlobStore store = newStore(H2PlatformTables.Mode.POSTGRESQL);
        byte[] body = "platform".getBytes(StandardCharsets.UTF_8);
        EntityBlobMetadata meta =
                store.put("", "scratch-item", "i-1", "file", body, "application/octet-stream");
        assertEquals("", meta.tenantId());
        assertArrayEquals(body, store.findContent("", meta.blobId()).orElseThrow());
        assertEquals(1, store.deleteForRecord("", "scratch-item", "i-1"));
    }

    @Test
    void rejectsOversizedContent() {
        EntityBlobStore store = newStore(H2PlatformTables.Mode.POSTGRESQL);
        byte[] huge = new byte[JdbcEntityBlobStore.MAX_BYTES + 1];
        assertThrows(
                IllegalArgumentException.class,
                () -> store.put("t1", "e1", "r1", "file", huge, "application/octet-stream"));
    }

    @Test
    void hybridAttrsRejectLargeStringField() {
        HybridEntityStore hybrid = new JdbcHybridEntityStore(
                new JdbcTemplate(H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL)),
                FIXED,
                new tools.jackson.databind.ObjectMapper());
        var entity = new com.subjex.entity.declare.EntityRenderer()
                .render(
                        """
                        entityKey: hybrid-asset
                        tableName: hybrid_asset
                        version: 1
                        permission: page.read
                        tenantScoped: true
                        storageMode: hybrid
                        fields:
                          - name: assetId
                            kind: text
                            required: true
                            maxLength: 64
                          - name: title
                            kind: text
                            required: true
                            maxLength: 8000
                        """);
        String big = "x".repeat(AttrsPayloadLimits.MAX_STRING_FIELD_CHARS + 1);
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> hybrid.save(entity, java.util.Map.of("assetId", "a-1", "title", big), "tenant-a"));
        assertTrue(ex.getMessage().contains("EntityBlobStore"));
    }

    private EntityBlobStore newStore(H2PlatformTables.Mode mode) {
        return new JdbcEntityBlobStore(
                new JdbcTemplate(H2PlatformTables.migrated(mode)),
                new LocalDirectoryObjectStorage(objectRoot),
                FIXED);
    }
}
