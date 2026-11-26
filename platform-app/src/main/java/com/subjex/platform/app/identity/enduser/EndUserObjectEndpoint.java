package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.entity.ChunkedBlobUploadSession;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.storage.ObjectDescriptor;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * EndUserObjectEndpoint — end-user objects under {@code /api/v1/end-user/objects}.
 * 终端对象列表、一次 multipart 上传，以及复用 CHUNK-1 的分片上传。
 * Download still uses existing {@code /download-links}.
 * 下载仍走既有签名链接。
 */
@RestController
public class EndUserObjectEndpoint {

    /** JSON/multipart base for end-user objects — 终端对象根路径. */
    public static final String PATH = JsonApi.BASE + "/end-user/objects";

    /** Chunked upload sessions (CHUNK-1 reuse) — 分片上传会话. */
    public static final String UPLOADS_PATH = PATH + "/uploads";

    public static final String UPLOAD_PATH = UPLOADS_PATH + "/{uploadId}";

    public static final String CHUNK_PATH = UPLOAD_PATH + "/chunks/{chunkIndex}";

    public static final String COMPLETE_PATH = UPLOAD_PATH + "/complete";

    private final EndUserObjectService objects;

    public EndUserObjectEndpoint(EndUserObjectService objects) {
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    @GetMapping(PATH)
    public ListDocument list(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        int lim = limit == null ? EndUserObjectService.DEFAULT_LIST_LIMIT : limit;
        int off = offset == null ? 0 : offset;
        List<ObjectDocument> items = objects
                .list(principal, resolveTenant(tenantHeader, tenantIdParam), lim, off)
                .stream()
                .map(ObjectDocument::from)
                .toList();
        return new ListDocument(items, lim, off);
    }

    @PostMapping(path = PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ObjectDocument upload(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestPart("file") MultipartFile file)
            throws IOException {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("multipart part 'file' is required");
        }
        String tenant = resolveTenant(tenantHeader, tenantIdParam);
        objects.rejectIfOversize(principal, tenant, file.getSize());
        EndUserObjectService.UploadedObject uploaded = objects.upload(
                principal,
                tenant,
                file.getBytes(),
                file.getContentType());
        return ObjectDocument.from(uploaded);
    }

    /**
     * Start a chunked session — body {@code contentType}, {@code totalBytes}.
     * 开启分片会话。
     */
    @PostMapping(value = UPLOADS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public UploadInitDocument initChunkedUpload(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody UploadInitRequest body) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        ChunkedBlobUploadSession session = objects.initChunkedUpload(
                principal,
                resolveTenant(tenantHeader, tenantIdParam),
                body.contentType(),
                body.totalBytes());
        return UploadInitDocument.from(session, objects.maxBytes());
    }

    /** Put one octet-stream chunk; same index retry is idempotent — 写入一分片，同下标可重试. */
    @PutMapping(
            value = CHUNK_PATH,
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ChunkAckDocument putChunk(
            @PathVariable("uploadId") String uploadId,
            @PathVariable("chunkIndex") int chunkIndex,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody(required = false) byte[] body) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        ChunkedBlobUploadSession updated = objects.putChunk(
                principal, resolveTenant(tenantHeader, tenantIdParam), uploadId, chunkIndex, body);
        return new ChunkAckDocument(
                updated.uploadId(), chunkIndex, updated.receivedIndices().size(), updated.chunkCount());
    }

    /** Assemble and register the owned object — 组装并登记为本人对象. */
    @PostMapping(value = COMPLETE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectDocument completeChunkedUpload(
            @PathVariable("uploadId") String uploadId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        return ObjectDocument.from(
                objects.completeChunkedUpload(principal, resolveTenant(tenantHeader, tenantIdParam), uploadId));
    }

    /** Abort an owned session — 中止本人分片会话. */
    @DeleteMapping(UPLOAD_PATH)
    public ResponseEntity<Void> abortChunkedUpload(
            @PathVariable("uploadId") String uploadId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        objects.abortChunkedUpload(principal, resolveTenant(tenantHeader, tenantIdParam), uploadId);
        return ResponseEntity.noContent().build();
    }

    private static String resolveTenant(String header, String param) {
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (param != null && !param.isBlank()) {
            return param.trim();
        }
        return null;
    }

    /** Paginated list — 分页列表. */
    public record ListDocument(List<ObjectDocument> objects, int limit, int offset) {}

    /** Object JSON — 对象文档. */
    public record ObjectDocument(
            String objectId,
            String objectKey,
            String ownerSubjectId,
            Instant createdAt,
            String mediaType,
            Integer sizeBytes) {
        static ObjectDocument from(EndUserObjectService.UploadedObject uploaded) {
            ObjectDescriptor d = uploaded.descriptor();
            return new ObjectDocument(
                    d.objectId(),
                    d.objectKey(),
                    d.ownerSubjectId(),
                    d.createdAt(),
                    uploaded.mediaType(),
                    uploaded.sizeBytes());
        }

        static ObjectDocument from(EndUserObjectService.ListedObject listed) {
            ObjectDescriptor d = listed.descriptor();
            return new ObjectDocument(
                    d.objectId(),
                    d.objectKey(),
                    d.ownerSubjectId(),
                    d.createdAt(),
                    listed.mediaType(),
                    null);
        }
    }

    /** Chunk session start body — 开启分片请求. */
    public record UploadInitRequest(String contentType, long totalBytes) {}

    /** Chunk session start response — 开启分片应答. */
    public record UploadInitDocument(
            String uploadId, long totalBytes, int chunkSize, int chunkCount, int maxBytes) {
        static UploadInitDocument from(ChunkedBlobUploadSession session, int maxBytes) {
            return new UploadInitDocument(
                    session.uploadId(),
                    session.totalBytes(),
                    session.chunkSize(),
                    session.chunkCount(),
                    maxBytes);
        }
    }

    /** Chunk write ack — 分片写入确认. */
    public record ChunkAckDocument(String uploadId, int chunkIndex, int receivedCount, int chunkCount) {}
}
