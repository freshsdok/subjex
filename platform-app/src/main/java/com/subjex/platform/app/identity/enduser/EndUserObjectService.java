package com.subjex.platform.app.identity.enduser;

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

/**
 * EndUserObjectService — WP7-2 end-user upload and list through {@link ObjectGrantStore}.
 * 终端对象上传与列表：写对象存储端口并登记所有者；列表仅本人拥有或未过期 SUBJECT 授权。
 */
public final class EndUserObjectService {

    /** Default list page size (inbox convention) — 列表默认页大小（与收件箱一致）. */
    public static final int DEFAULT_LIST_LIMIT = 50;

    /** Max list page size — 列表页上限. */
    public static final int MAX_LIST_LIMIT = 200;

    private final EndUserAccountStore accounts;
    private final ObjectStorage storage;
    private final ObjectGrantStore grants;
    private final ObjectUploadLimits limits;
    private final ObjectAccessAudit audit;

    public EndUserObjectService(
            EndUserAccountStore accounts,
            ObjectStorage storage,
            ObjectGrantStore grants,
            ObjectUploadLimits limits,
            ObjectAccessAudit audit) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.limits = limits == null ? ObjectUploadLimits.defaults() : limits;
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /**
     * Membership + declared-size check before reading a large multipart body.
     * 读入大体前先确认成员关系并按申报大小拦截。
     */
    public void rejectIfOversize(EndUserPrincipal principal, String tenantId, long declaredSize) {
        Objects.requireNonNull(principal, "principal");
        Identity identity = accounts.requireActiveMembership(principal.accountId(), tenantId);
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

    /**
     * Put bytes, register the end-user subject as owner, and audit.
     * 写入字节、以终端主体为所有者登记，并记审计。不走分块上传。
     */
    public UploadedObject upload(
            EndUserPrincipal principal, String tenantId, byte[] content, String contentType) {
        Objects.requireNonNull(principal, "principal");
        Identity identity = accounts.requireActiveMembership(principal.accountId(), tenantId);
        String tid = identity.tenantId();
        byte[] body = content == null ? new byte[0] : content;
        if (body.length > limits.maxBytes()) {
            audit.recordUpload(
                    tid, identity.identityId(), "maxBytes=" + limits.maxBytes(), null, AuditOutcome.REFUSED);
            throw ObjectUploadRejectedException.tooLarge(limits.maxBytes());
        }
        String type;
        try {
            type = ObjectContentTypes.requireAllowed(contentType);
        } catch (IllegalArgumentException ex) {
            audit.recordUpload(
                    tid,
                    identity.identityId(),
                    "contentType=" + (contentType == null ? "" : contentType),
                    null,
                    AuditOutcome.REFUSED);
            throw ObjectUploadRejectedException.contentType(contentType);
        }
        String objectKey = "eu-" + principal.subjectId() + "-" + UUID.randomUUID();
        storage.put(tid, objectKey, body, type);
        ObjectDescriptor descriptor;
        try {
            descriptor = grants.register(tid, objectKey, principal.subjectId());
        } catch (RuntimeException ex) {
            storage.delete(tid, objectKey);
            throw ex;
        }
        audit.recordUpload(tid, identity.identityId(), objectKey, null, AuditOutcome.ALLOWED);
        return new UploadedObject(descriptor, type, body.length);
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

    /** Upload result — 上传结果. */
    public record UploadedObject(ObjectDescriptor descriptor, String mediaType, int sizeBytes) {}

    /** List row — 列表行. */
    public record ListedObject(ObjectDescriptor descriptor, String mediaType) {}
}
