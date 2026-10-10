package com.subjex.platform.app.entity;

import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.storage.StoredObject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcEntityBlobStore — JDBC {@code entity_blob} + {@link ObjectStorage}（ES-2 / ADR 0002）。
 * <p>
 * ObjectStorage rejects blank tenant ids; blank metadata tenant maps to {@link #OBJECT_TENANT_PLATFORM}.
 * Field names are fail-closed identifiers. Max payload {@link #MAX_BYTES}.
 * ObjectStorage 拒绝空白租户；元数据空租户映射为 {@link #OBJECT_TENANT_PLATFORM}。字段名失败关闭；载荷上限见 MAX_BYTES。
 */
public final class JdbcEntityBlobStore implements EntityBlobStore {

    /** ObjectStorage tenant when metadata tenant_id is empty — 元数据空租户时的对象存储租户名。 */
    public static final String OBJECT_TENANT_PLATFORM = "_platform_";

    /** Max attachment bytes per put (ES-2) — 单次 put 最大字节（ES-2）。 */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final Pattern FIELD_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,127}");

    private final JdbcTemplate jdbc;
    private final ObjectStorage objects;
    private final Clock clock;

    public JdbcEntityBlobStore(JdbcTemplate jdbc, ObjectStorage objects, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public EntityBlobMetadata put(
            String tenantId,
            String entityKey,
            String recordId,
            String fieldName,
            byte[] content,
            String contentType) {
        String tid = normalizeTenant(tenantId);
        requireToken(entityKey, "entityKey");
        requireToken(recordId, "recordId");
        if (fieldName == null || !FIELD_NAME.matcher(fieldName).matches()) {
            throw new IllegalArgumentException("fieldName must be an identifier");
        }
        byte[] bytes = content == null ? new byte[0] : content;
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("content exceeds maxBytes=" + MAX_BYTES);
        }
        String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType.trim();
        if (type.length() > 128) {
            throw new IllegalArgumentException("contentType too long");
        }
        String blobId = UUID.randomUUID().toString();
        String storageKey = blobId;
        String checksum = sha256Hex(bytes);
        Timestamp now = PlatformTables.timestamp(clock.instant());
        objects.put(objectTenant(tid), storageKey, bytes, type);
        jdbc.update(
                """
                INSERT INTO entity_blob
                  (blob_id, tenant_id, entity_key, record_id, field_name, content_type,
                   byte_size, storage_key, checksum_sha256, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                blobId,
                tid,
                entityKey.trim(),
                recordId.trim(),
                fieldName,
                type,
                bytes.length,
                storageKey,
                checksum,
                now);
        return new EntityBlobMetadata(
                blobId,
                tid,
                entityKey.trim(),
                recordId.trim(),
                fieldName,
                type,
                bytes.length,
                storageKey,
                checksum,
                now.toInstant());
    }

    @Override
    public Optional<EntityBlobMetadata> findMetadata(String tenantId, String blobId) {
        String tid = normalizeTenant(tenantId);
        Objects.requireNonNull(blobId, "blobId");
        List<EntityBlobMetadata> rows = jdbc.query(
                """
                SELECT blob_id, tenant_id, entity_key, record_id, field_name, content_type,
                       byte_size, storage_key, checksum_sha256, created_at
                  FROM entity_blob
                 WHERE tenant_id = ? AND blob_id = ?
                """,
                mapper(),
                tid,
                blobId);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<byte[]> findContent(String tenantId, String blobId) {
        return findMetadata(tenantId, blobId).flatMap(meta -> objects
                .find(objectTenant(meta.tenantId()), meta.storageKey())
                .map(StoredObject::content));
    }

    @Override
    public List<EntityBlobMetadata> listForRecord(String tenantId, String entityKey, String recordId) {
        String tid = normalizeTenant(tenantId);
        requireToken(entityKey, "entityKey");
        requireToken(recordId, "recordId");
        return jdbc.query(
                """
                SELECT blob_id, tenant_id, entity_key, record_id, field_name, content_type,
                       byte_size, storage_key, checksum_sha256, created_at
                  FROM entity_blob
                 WHERE tenant_id = ? AND entity_key = ? AND record_id = ?
                 ORDER BY created_at ASC, blob_id ASC
                """,
                mapper(),
                tid,
                entityKey.trim(),
                recordId.trim());
    }

    @Override
    public boolean delete(String tenantId, String blobId) {
        Optional<EntityBlobMetadata> meta = findMetadata(tenantId, blobId);
        if (meta.isEmpty()) {
            return false;
        }
        EntityBlobMetadata row = meta.get();
        objects.delete(objectTenant(row.tenantId()), row.storageKey());
        return jdbc.update(
                        "DELETE FROM entity_blob WHERE tenant_id = ? AND blob_id = ?",
                        row.tenantId(),
                        row.blobId())
                > 0;
    }

    @Override
    public int deleteForRecord(String tenantId, String entityKey, String recordId) {
        List<EntityBlobMetadata> rows = listForRecord(tenantId, entityKey, recordId);
        int removed = 0;
        for (EntityBlobMetadata row : rows) {
            if (delete(row.tenantId(), row.blobId())) {
                removed++;
            }
        }
        return removed;
    }

    private static RowMapper<EntityBlobMetadata> mapper() {
        return (rs, rowNum) -> new EntityBlobMetadata(
                rs.getString("blob_id"),
                rs.getString("tenant_id"),
                rs.getString("entity_key"),
                rs.getString("record_id"),
                rs.getString("field_name"),
                rs.getString("content_type"),
                rs.getInt("byte_size"),
                rs.getString("storage_key"),
                rs.getString("checksum_sha256"),
                rs.getTimestamp("created_at").toInstant());
    }

    /** Blank → empty string (matches hybrid platform sentinel) — 空白→空串（与混合轨占位一致）。 */
    static String normalizeTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return JdbcHybridEntityStore.PLATFORM_TENANT;
        }
        return tenantId.trim();
    }

    static String objectTenant(String metadataTenantId) {
        if (metadataTenantId == null || metadataTenantId.isBlank()) {
            return OBJECT_TENANT_PLATFORM;
        }
        return metadataTenantId;
    }

    private static void requireToken(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " required");
        }
        String trimmed = value.trim();
        if (trimmed.contains("..") || trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(label + " must be a single token");
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
