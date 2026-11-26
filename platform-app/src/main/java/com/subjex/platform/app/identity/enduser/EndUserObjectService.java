package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.entity.ChunkedBlobUploadSession;
import com.subjex.platform.app.entity.ChunkedBlobUploadStore;
import com.subjex.platform.app.storage.ObjectAccessAudit;
import com.subjex.platform.app.storage.ObjectContentTypes;
import com.subjex.platform.app.storage.ObjectDescriptor;
import com.subjex.platform.app.storage.ObjectGrantStore;
import com.subjex.platform.app.storage.ObjectUploadLimits;
import com.subjex.platform.app.storage.ObjectUploadRejectedException;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.identity.Identity;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.storage.StoredObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * EndUserObjectService — end-user upload and list through {@link ObjectGrantStore}.
 * 终端对象上传与列表：写对象存储端口并登记所有者；列表仅本人拥有或未过期 SUBJECT 授权。
 * <p>
 * Chunked upload reuses the existing CHUNK-1 session store (init / putChunk / assemble),
 * then {@link ObjectStorage#put} + grant register. It is not the AWS S3 multipart protocol.
 * Sessions stay process-local with a local temp directory, same as operator entity / inbox
 * uploads; multi-replica needs sticky routing or a later shared session store.
 * 分片复用已有 CHUNK-1 会话存储，组装后再 put 并登记；不是 S3 multipart 协议。
 * 会话仍是进程内 + 本地临时目录，与操作员实体/站内信分片相同。
 */
public final class EndUserObjectService {

    /** Default list page size (inbox convention) — 列表默认页大小（与收件箱一致）. */
    public static final int DEFAULT_LIST_LIMIT = 50;

    /** Max list page size — 列表页上限. */
    public static final int MAX_LIST_LIMIT = 200;

    /**
     * Sentinel entity key so end-user sessions are not mixed with inbox/entity ones.
     * 哨兵实体键，避免与站内信/实体分片会话混用。
     */
    static final String CHUNK_ENTITY_KEY = "end-user-object";

    /** Fixed field token for the chunk session — 分片会话固定字段名. */
    static final String CHUNK_FIELD_NAME = "file";

    private final EndUserAccountStore accounts;
    private final ObjectStorage storage;
    private final ObjectGrantStore grants;
    private final ObjectUploadLimits limits;
    private final ObjectAccessAudit audit;
    private final ChunkedBlobUploadStore chunkedUploads;

    public EndUserObjectService(
            EndUserAccountStore accounts,
            ObjectStorage storage,
            ObjectGrantStore grants,
            ObjectUploadLimits limits,
            ObjectAccessAudit audit,
            ChunkedBlobUploadStore chunkedUploads) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.limits = limits == null ? ObjectUploadLimits.defaults() : limits;
        this.audit = Objects.requireNonNull(audit, "audit");
        this.chunkedUploads = Objects.requireNonNull(chunkedUploads, "chunkedUploads");
    }

    /**
     * Membership + declared-size check before reading a large multipart body.
     * 读入大体前先确认成员关系并按申报大小拦截。
     */
    public void rejectIfOversize(EndUserPrincipal principal, String tenantId, long declaredSize) {
        Objects.requireNonNull(principal, "principal");
        Identity identity = accounts.requireActiveMembership(principal.accountId(), tenantId);
        rejectOversize(identity, declaredSize);
    }

    /**
     * Put bytes, register the end-user subject as owner, and audit.
     * 写入字节、以终端主体为所有者登记，并记审计。小文件仍可走一次 multipart。
     */
    public UploadedObject upload(
            EndUserPrincipal principal, String tenantId, byte[] content, String contentType) {
        Objects.requireNonNull(principal, "principal");
        Identity identity = accounts.requireActiveMembership(principal.accountId(), tenantId);
        byte[] body = content == null ? new byte[0] : content;
        rejectOversize(identity, body.length);
        String type = requireAllowedType(identity, contentType);
        return storeOwned(identity, principal.subjectId(), body, type);
    }

    /**
     * Start a CHUNK-1 session owned by this subject in the current tenant.
     * 为当前主体在本租户开启分片会话。
     */
    public ChunkedBlobUploadSession initChunkedUpload(
            EndUserPrincipal principal, String tenantId, String contentType, long totalBytes) {
        Objects.requireNonNull(principal, "principal");
        Identity identity = accounts.requireActiveMembership(principal.accountId(), tenantId);
        rejectOversize(identity, totalBytes);
        String type = requireAllowedType(identity, contentType);
        return chunkedUploads.init(
                identity.tenantId(),
                CHUNK_ENTITY_KEY,
                principal.subjectId(),
                CHUNK_FIELD_NAME,
                type,
                totalBytes,
                limits.maxBytes());
    }

    /** Put one chunk on an owned session — 向本人会话写入一分片. */
    public ChunkedBlobUploadSession putChunk(
            EndUserPrincipal principal, String tenantId, String uploadId, int chunkIndex, byte[] data) {
        Objects.requireNonNull(principal, "principal");
        Identity identity = accounts.requireActiveMembership(principal.accountId(), tenantId);
        requireOwnedChunkSession(identity.tenantId(), principal.subjectId(), uploadId);
        return chunkedUploads.putChunk(uploadId, chunkIndex, data == null ? new byte[0] : data);
    }

    /**
     * Assemble chunks, put + register like a single-shot upload, then abort the session.
     * 组装分片，按一次上传登记所有者，然后清理会话。
     */
    public UploadedObject completeChunkedUpload(EndUserPrincipal principal, String tenantId, String uploadId) {
        Objects.requireNonNull(principal, "principal");
        Identity identity = accounts.requireActiveMembership(principal.accountId(), tenantId);
        ChunkedBlobUploadSession session =
                requireOwnedChunkSession(identity.tenantId(), principal.subjectId(), uploadId);
        String type = requireAllowedType(identity, session.contentType());
        byte[] assembled = chunkedUploads.assemble(uploadId);
        try {
            if (assembled.length == 0) {
                throw new IllegalArgumentException("empty object body");
            }
            rejectOversize(identity, assembled.length);
            return storeOwned(identity, principal.subjectId(), assembled, type);
        } finally {
            chunkedUploads.abort(uploadId);
        }
    }

    /** Abort an owned chunk session — 中止本人分片会话. */
    public void abortChunkedUpload(EndUserPrincipal principal, String tenantId, String uploadId) {
        Objects.requireNonNull(principal, "principal");
        Identity identity = accounts.requireActiveMembership(principal.accountId(), tenantId);
        requireOwnedChunkSession(identity.tenantId(), principal.subjectId(), uploadId);
        chunkedUploads.abort(uploadId);
    }

    /**
     * List owned or SUBJECT-granted objects in the current tenant (not inbox / unowned entity_blob).
     * 列出当前租户本人拥有或 SUBJECT 授权的对象（不含站内信附件与未登记 entity_blob）。
     */
    public List<ListedObject> list(EndUserPrincipal principal, String tenantId, int limit, int offset) {
        Objects.requireNonNull(principal, "principal");
        Identity identity = accounts.requireActiveMembership(principal.accountId(), tenantId);
        int lim = limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        int off = Math.max(offset, 0);
        List<ObjectDescriptor> rows =
                grants.listOwnedOrSubjectGranted(identity.tenantId(), principal.subjectId(), lim, off);
        List<ListedObject> items = new ArrayList<>(rows.size());
        for (ObjectDescriptor row : rows) {
            String mediaType = storage
                    .find(row.tenantId(), row.objectKey())
                    .map(StoredObject::mediaType)
                    .orElse(null);
            items.add(new ListedObject(row, mediaType));
        }
        return List.copyOf(items);
    }

    public int maxBytes() {
        return limits.maxBytes();
    }

    private void rejectOversize(Identity identity, long declaredSize) {
        if (declaredSize > limits.maxBytes()) {
            audit.recordUpload(
                    identity.tenantId(),
                    identity.identityId(),
                    "maxBytes=" + limits.maxBytes(),
                    null,
                    AuditOutcome.REFUSED);
            throw ObjectUploadRejectedException.tooLarge(limits.maxBytes());
        }
    }

    private String requireAllowedType(Identity identity, String contentType) {
        try {
            return ObjectContentTypes.requireAllowed(contentType);
        } catch (IllegalArgumentException ex) {
            audit.recordUpload(
                    identity.tenantId(),
                    identity.identityId(),
                    "contentType=" + (contentType == null ? "" : contentType),
                    null,
                    AuditOutcome.REFUSED);
            throw ObjectUploadRejectedException.contentType(contentType);
        }
    }

    private UploadedObject storeOwned(Identity identity, String ownerSubjectId, byte[] body, String type) {
        String tid = identity.tenantId();
        String objectKey = "eu-" + ownerSubjectId + "-" + UUID.randomUUID();
        storage.put(tid, objectKey, body, type);
        ObjectDescriptor descriptor;
        try {
            descriptor = grants.register(tid, objectKey, ownerSubjectId);
        } catch (RuntimeException ex) {
            storage.delete(tid, objectKey);
            throw ex;
        }
        audit.recordUpload(tid, identity.identityId(), objectKey, null, AuditOutcome.ALLOWED);
        return new UploadedObject(descriptor, type, body.length);
    }

    private ChunkedBlobUploadSession requireOwnedChunkSession(String tenantId, String subjectId, String uploadId) {
        ChunkedBlobUploadSession session = chunkedUploads
                .find(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "upload session not found"));
        if (!tenantId.equals(session.blobTenant())
                || !CHUNK_ENTITY_KEY.equals(session.entityKey())
                || !subjectId.equals(session.recordId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "upload session not found");
        }
        return session;
    }

    /** Upload result — 上传结果. */
    public record UploadedObject(ObjectDescriptor descriptor, String mediaType, int sizeBytes) {}

    /** List row — 列表行. */
    public record ListedObject(ObjectDescriptor descriptor, String mediaType) {}
}
