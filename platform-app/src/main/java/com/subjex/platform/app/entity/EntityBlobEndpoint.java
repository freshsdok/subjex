package com.subjex.platform.app.entity;

import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.declaration.TenantDeclarationContext;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.AccessResource;
import com.subjex.platform.app.security.DeclarationAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.storage.ObjectAccessAudit;
import com.subjex.platform.app.storage.ObjectContentTypes;
import com.subjex.platform.app.storage.ObjectGrantStore;
import com.subjex.platform.app.storage.ObjectUploadLimits;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * EntityBlobEndpoint — 实体附件 REST（ES-3 / ADR 0002）：上传/下载/列举/删除，AuthZ 与实体声明权限一致。
 * <p>
 * Paths under {@code /api/v1/entities/{entityKey}/records/{id}/blobs}. Bytes via {@link EntityBlobStore};
 * never written into attrs. Upload: octet-stream, multipart (SHARED-3), or chunked sessions (CHUNK-1).
 * 路径见上。字节走 EntityBlobStore。上传：octet-stream / multipart / 分片会话（CHUNK-1）。
 * AuthZ via {@link DeclarationAccess#requireVia} / {@link PolicyEngine} (AuthZ-1f).
 * 鉴权经 PolicyEngine（AuthZ-1f）。
 */
@RestController
public class EntityBlobEndpoint {

    public static final String BLOBS_PATH = JsonApi.BASE + "/entities/{entityKey}/records/{id}/blobs";
    public static final String BLOB_PATH = BLOBS_PATH + "/{blobId}";
    /** Chunked upload sessions (CHUNK-1) — 分片上传会话。 */
    public static final String UPLOADS_PATH = BLOBS_PATH + "/uploads";
    public static final String UPLOAD_PATH = UPLOADS_PATH + "/{uploadId}";
    public static final String CHUNK_PATH = UPLOAD_PATH + "/chunks/{chunkIndex}";
    public static final String COMPLETE_PATH = UPLOAD_PATH + "/complete";

    private final EffectiveDeclarationService effective;
    private final EntityBlobStore blobs;
    private final GenericEntityStore tableStore;
    private final HybridEntityStore hybridStore;
    private final OperatorTenantAccess tenantAccess;
    private final ChunkedBlobUploadStore chunkedUploads;
    private final PolicyEngine policyEngine;
    private final ObjectUploadLimits uploadLimits;
    private final AuthorizedObjectRegistrar authorizedUploads;
    private final ObjectAccessAudit objectAudit;

    public EntityBlobEndpoint(
            EffectiveDeclarationService effective,
            EntityBlobStore blobs,
            GenericEntityStore tableStore,
            HybridEntityStore hybridStore,
            OperatorTenantAccess tenantAccess,
            ChunkedBlobUploadStore chunkedUploads,
            PolicyEngine policyEngine,
            ObjectUploadLimits uploadLimits,
            AuthorizedObjectRegistrar authorizedUploads,
            ObjectAccessAudit objectAudit) {
        this.effective = Objects.requireNonNull(effective, "effective");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.hybridStore = Objects.requireNonNull(hybridStore, "hybridStore");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.chunkedUploads = Objects.requireNonNull(chunkedUploads, "chunkedUploads");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
        this.uploadLimits = uploadLimits == null ? ObjectUploadLimits.defaults() : uploadLimits;
        this.authorizedUploads = Objects.requireNonNull(authorizedUploads, "authorizedUploads");
        this.objectAudit = Objects.requireNonNull(objectAudit, "objectAudit");
    }

    /** List blob metadata for a record — 列出记录的附件元数据。 */
    @GetMapping(BLOBS_PATH)
    public BlobsDocument list(
            @PathVariable("entityKey") String entityKey,
            @PathVariable("id") String id,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        Context ctx = requireContext(entityKey, id, operator, tenantId, false);
        return new BlobsDocument(
                blobs.listForRecord(ctx.blobTenant(), entityKey, id).stream()
                        .map(BlobMetadataDocument::from)
                        .toList());
    }

    /** Upload raw body as attachment — 上传原始字节为附件。 */
    @PostMapping(value = BLOBS_PATH, consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BlobMetadataDocument> upload(
            @PathVariable("entityKey") String entityKey,
            @PathVariable("id") String id,
            @RequestParam("fieldName") String fieldName,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestBody(required = false) byte[] body,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        Context ctx = requireContext(entityKey, id, operator, tenantId, true);
        String type = contentType == null || contentType.isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : contentType;
        // Strip charset etc. for storage media type length — 存储用媒体类型去掉参数
        int semi = type.indexOf(';');
        if (semi >= 0) {
            type = type.substring(0, semi).trim();
        }
        EntityBlobMetadata meta = storeNewBlob(
                ctx, entityKey, id, fieldName, body == null ? new byte[0] : body, type, operator);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(BlobMetadataDocument.from(meta));
    }


    /**
     * Upload multipart file part {@code file} (SHARED-3) — multipart 上传 {@code file} 部件（SHARED-3）。
     * <p>
     * Form fields: {@code file} (required), {@code fieldName} (optional, default {@code attachment}).
     * Size capped by {@link ObjectUploadLimits} ({@code platform.storage.upload.max-bytes}).
     * 表单：{@code file} 必填；{@code fieldName} 可选（默认 attachment）。大小上限见上传配置。
     */
    @PostMapping(
            value = BLOBS_PATH,
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BlobMetadataDocument> uploadMultipart(
            @PathVariable("entityKey") String entityKey,
            @PathVariable("id") String id,
            @RequestParam(value = "fieldName", defaultValue = "attachment") String fieldName,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId)
            throws java.io.IOException {
        Context ctx = requireContext(entityKey, id, operator, tenantId, true);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("multipart part 'file' is required");
        }
        if (file.getSize() > uploadLimits.maxBytes()) {
            throw new IllegalArgumentException(
                    "content exceeds maxBytes=" + uploadLimits.maxBytes());
        }
        byte[] body = file.getBytes();
        String type = file.getContentType();
        if (type == null || type.isBlank()) {
            type = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        int semi = type.indexOf(';');
        if (semi >= 0) {
            type = type.substring(0, semi).trim();
        }
        EntityBlobMetadata meta = storeNewBlob(ctx, entityKey, id, fieldName, body, type, operator);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(BlobMetadataDocument.from(meta));
    }


    /**
     * CHUNK-1: start chunked upload session — 开启分片上传会话。
     * <p>
     * Body: {@code fieldName}, {@code contentType}, {@code totalBytes} (0..configured max).
     */
    @PostMapping(value = UPLOADS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public UploadInitDocument initChunkedUpload(
            @PathVariable("entityKey") String entityKey,
            @PathVariable("id") String id,
            @RequestBody UploadInitRequest body,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        Context ctx = requireContext(entityKey, id, operator, tenantId, true);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        String fieldName = body.fieldName() == null || body.fieldName().isBlank() ? "attachment" : body.fieldName().trim();
        String type = body.contentType() == null || body.contentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : body.contentType().trim();
        int semi = type.indexOf(';');
        if (semi >= 0) {
            type = type.substring(0, semi).trim();
        }
        type = requireAllowedTypeForTrack(ctx.blobTenant(), type, operator, entityKey);
        ChunkedBlobUploadSession session = chunkedUploads.init(
                ctx.blobTenant(),
                entityKey,
                id,
                fieldName,
                type,
                body.totalBytes(),
                uploadLimits.maxBytes());
        return UploadInitDocument.from(session, uploadLimits.maxBytes());
    }

    /**
     * CHUNK-1: put one chunk (octet-stream); same index retry is idempotent —
     * 写入一分片；同下标重试可覆盖。
     */
    @PutMapping(value = CHUNK_PATH, consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChunkAckDocument putChunk(
            @PathVariable("entityKey") String entityKey,
            @PathVariable("id") String id,
            @PathVariable("uploadId") String uploadId,
            @PathVariable("chunkIndex") int chunkIndex,
            @RequestBody(required = false) byte[] body,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        Context ctx = requireContext(entityKey, id, operator, tenantId, true);
        ChunkedBlobUploadSession session = requireOwnedSession(ctx, entityKey, id, uploadId);
        ChunkedBlobUploadSession updated = chunkedUploads.putChunk(session.uploadId(), chunkIndex, body);
        return new ChunkAckDocument(
                updated.uploadId(), chunkIndex, updated.receivedIndices().size(), updated.chunkCount());
    }

    /**
     * CHUNK-1: assemble chunks → EntityBlobStore.put → abort session —
     * 组装分片并写入附件存储，然后清理会话。
     */
    @PostMapping(value = COMPLETE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BlobMetadataDocument> completeChunkedUpload(
            @PathVariable("entityKey") String entityKey,
            @PathVariable("id") String id,
            @PathVariable("uploadId") String uploadId,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        Context ctx = requireContext(entityKey, id, operator, tenantId, true);
        ChunkedBlobUploadSession session = requireOwnedSession(ctx, entityKey, id, uploadId);
        byte[] assembled = chunkedUploads.assemble(session.uploadId());
        try {
            EntityBlobMetadata meta = storeNewBlob(
                    ctx, entityKey, id, session.fieldName(), assembled, session.contentType(), operator);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(BlobMetadataDocument.from(meta));
        } finally {
            chunkedUploads.abort(session.uploadId());
        }
    }

    /** CHUNK-1: abort upload session — 中止并清理分片会话。 */
    @DeleteMapping(UPLOAD_PATH)
    public ResponseEntity<Void> abortChunkedUpload(
            @PathVariable("entityKey") String entityKey,
            @PathVariable("id") String id,
            @PathVariable("uploadId") String uploadId,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        Context ctx = requireContext(entityKey, id, operator, tenantId, true);
        requireOwnedSession(ctx, entityKey, id, uploadId);
        chunkedUploads.abort(uploadId);
        return ResponseEntity.noContent().build();
    }

    private ChunkedBlobUploadSession requireOwnedSession(
            Context ctx, String entityKey, String id, String uploadId) {
        ChunkedBlobUploadSession session = chunkedUploads
                .find(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!ctx.blobTenant().equals(session.blobTenant())
                || !entityKey.equals(session.entityKey())
                || !id.equals(session.recordId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return session;
    }

    /** Download attachment bytes — 下载附件字节。 */
    @GetMapping(BLOB_PATH)
    public ResponseEntity<byte[]> download(
            @PathVariable("entityKey") String entityKey,
            @PathVariable("id") String id,
            @PathVariable("blobId") String blobId,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        Context ctx = requireContext(entityKey, id, operator, tenantId, false);
        EntityBlobMetadata meta = blobs.findMetadata(ctx.blobTenant(), blobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!entityKey.equals(meta.entityKey()) || !id.equals(meta.recordId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        byte[] content = blobs.findContent(ctx.blobTenant(), blobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(meta.contentType()))
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length))
                .body(content);
    }

    /** Delete one attachment — 删除单个附件。 */
    @DeleteMapping(BLOB_PATH)
    public ResponseEntity<Void> delete(
            @PathVariable("entityKey") String entityKey,
            @PathVariable("id") String id,
            @PathVariable("blobId") String blobId,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        Context ctx = requireContext(entityKey, id, operator, tenantId, true);
        EntityBlobMetadata meta = blobs.findMetadata(ctx.blobTenant(), blobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!entityKey.equals(meta.entityKey()) || !id.equals(meta.recordId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!blobs.delete(ctx.blobTenant(), blobId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    private Context requireContext(
            String entityKey, String id, OperatorPrincipal operator, String tenantId, boolean write) {
        if (TenantDeclarationContext.overlayRequested(tenantId)) {
            tenantAccess.requireGranted(operator, tenantId.trim());
        }
        RenderedEntity entity = effective
                .runtimeEntity(tenantId, entityKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // AuthZ-1f: PolicyEngine (Cedar default) — 经 PolicyEngine。
        DeclarationAccess.requireVia(
                policyEngine,
                operator,
                entity.permission(),
                entity.tenantScoped(),
                tenantId,
                AccessResource.of("entity", entityKey),
                AccessAction.of(write ? "blob-write" : "blob-read"));
        String storeTenant = entity.tenantScoped() ? tenantId : null;
        boolean exists = entity.sharedRecordStorage()
                ? hybridStore.findById(entity, id, storeTenant).isPresent()
                : tableStore.findById(entity, id, storeTenant).isPresent();
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String blobTenant = entity.tenantScoped()
                ? Objects.requireNonNull(tenantId, "tenantId").trim()
                : JdbcHybridEntityStore.PLATFORM_TENANT;
        return new Context(entity, blobTenant);
    }

    private record Context(RenderedEntity entity, String blobTenant) {}


    /**
     * Authorized-track uploads go through MIME + register + audit; unscoped sentinels stay legacy.
     * 授权轨走 MIME、登记与审计；未限定占位仍走既有路径，不回填。
     */
    private EntityBlobMetadata storeNewBlob(
            Context ctx,
            String entityKey,
            String recordId,
            String fieldName,
            byte[] content,
            String contentType,
            OperatorPrincipal operator) {
        String objectTenant = JdbcEntityBlobStore.objectTenant(ctx.blobTenant());
        if (isAuthorizedTrackTenant(objectTenant)) {
            return authorizedUploads.putRegistered(
                    ctx.blobTenant(),
                    entityKey,
                    recordId,
                    fieldName,
                    content,
                    contentType,
                    operator.subjectId(),
                    operator.identityId());
        }
        return blobs.put(ctx.blobTenant(), entityKey, recordId, fieldName, content, contentType);
    }

    /**
     * Enforce the shared MIME allowlist only on the authorized track (fail closed).
     * 仅在授权轨强制共用 MIME 白名单（失败关闭）。
     */
    private String requireAllowedTypeForTrack(
            String blobTenant, String contentType, OperatorPrincipal operator, String entityKey) {
        String objectTenant = JdbcEntityBlobStore.objectTenant(blobTenant);
        if (!isAuthorizedTrackTenant(objectTenant)) {
            return contentType;
        }
        try {
            return ObjectContentTypes.requireAllowed(contentType);
        } catch (IllegalArgumentException ex) {
            objectAudit.recordUpload(
                    objectTenant,
                    operator.identityId(),
                    "contentType=" + (contentType == null ? "" : contentType),
                    entityKey,
                    AuditOutcome.REFUSED);
            throw ex;
        }
    }

    /**
     * True when the object-storage tenant can be an authorized-track home (not blank, not
     * reserved {@code platform}, not the unscoped {@code _platform_} sentinel).
     * 是否可作为授权轨归属租户（非空、非保留 platform、非未限定 {@code _platform_} 占位）。
     */
    static boolean isAuthorizedTrackTenant(String objectTenant) {
        if (objectTenant == null || objectTenant.isBlank()) {
            return false;
        }
        return !JdbcEntityBlobStore.OBJECT_TENANT_PLATFORM.equals(objectTenant)
                && !ObjectGrantStore.RESERVED_TENANT.equals(objectTenant);
    }


    /** BlobsDocument — 附件元数据列表。 */
    /** JSON-friendly blob metadata (Instant as ISO-8601 string) — JSON 友好元数据。 */
    public record BlobMetadataDocument(
            String blobId,
            String tenantId,
            String entityKey,
            String recordId,
            String fieldName,
            String contentType,
            int byteSize,
            String storageKey,
            String checksumSha256,
            String createdAt) {
        static BlobMetadataDocument from(EntityBlobMetadata meta) {
            return new BlobMetadataDocument(
                    meta.blobId(),
                    meta.tenantId(),
                    meta.entityKey(),
                    meta.recordId(),
                    meta.fieldName(),
                    meta.contentType(),
                    meta.byteSize(),
                    meta.storageKey(),
                    meta.checksumSha256(),
                    meta.createdAt().toString());
        }
    }

    /** BlobsDocument — 附件元数据列表。 */
    public record BlobsDocument(List<BlobMetadataDocument> blobs) {}

    /** CHUNK-1 init body — 分片上传初始化请求。 */
    public record UploadInitRequest(String fieldName, String contentType, long totalBytes) {}

    /** CHUNK-1 init reply — 分片上传初始化响应。 */
    public record UploadInitDocument(
            String uploadId, int chunkSize, int chunkCount, long totalBytes, int maxBytes) {
        static UploadInitDocument from(ChunkedBlobUploadSession session, int maxBytes) {
            return new UploadInitDocument(
                    session.uploadId(),
                    session.chunkSize(),
                    session.chunkCount(),
                    session.totalBytes(),
                    maxBytes);
        }
    }

    /** CHUNK-1 chunk ack — 分片写入回执。 */
    public record ChunkAckDocument(String uploadId, int chunkIndex, int receivedCount, int chunkCount) {}
}
