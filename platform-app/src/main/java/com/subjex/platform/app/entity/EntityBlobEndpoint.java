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
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.tenant.TenantGuard;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * EntityBlobEndpoint — 实体附件 REST（ES-3 / ADR 0002）：上传/下载/列举/删除，AuthZ 与实体声明权限一致。
 * <p>
 * Paths under {@code /api/v1/entities/{entityKey}/records/{id}/blobs}. Bytes via {@link EntityBlobStore};
 * never written into hybrid attrs. Upload requires the parent record to exist (table or hybrid).
 * 路径见上。字节走 EntityBlobStore，不进 attrs。上传要求父记录已存在。
 */
@RestController
public class EntityBlobEndpoint {

    public static final String BLOBS_PATH = JsonApi.BASE + "/entities/{entityKey}/records/{id}/blobs";
    public static final String BLOB_PATH = BLOBS_PATH + "/{blobId}";

    private final EffectiveDeclarationService effective;
    private final EntityBlobStore blobs;
    private final GenericEntityStore tableStore;
    private final HybridEntityStore hybridStore;
    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;

    public EntityBlobEndpoint(
            EffectiveDeclarationService effective,
            EntityBlobStore blobs,
            GenericEntityStore tableStore,
            HybridEntityStore hybridStore,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess) {
        this.effective = Objects.requireNonNull(effective, "effective");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.hybridStore = Objects.requireNonNull(hybridStore, "hybridStore");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
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
        EntityBlobMetadata meta =
                blobs.put(ctx.blobTenant(), entityKey, id, fieldName, body == null ? new byte[0] : body, type);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(BlobMetadataDocument.from(meta));
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
        DeclarationAccess.require(
                operator,
                entity.permission(),
                entity.tenantScoped(),
                tenantId,
                tenantGuard,
                tenantAccess,
                AccessResource.of("entity", entityKey),
                AccessAction.of(write ? "blob-write" : "blob-read"));
        String storeTenant = entity.tenantScoped() ? tenantId : null;
        boolean exists = entity.hybridStorage()
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
}
