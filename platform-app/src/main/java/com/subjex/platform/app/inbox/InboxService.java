package com.subjex.platform.app.inbox;

import com.subjex.platform.app.entity.ChunkedBlobUploadSession;
import com.subjex.platform.app.entity.ChunkedBlobUploadStore;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.inbox.InboxAttachment;
import com.subjex.platform.contract.inbox.InboxAttachmentBind;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxGroup;
import com.subjex.platform.contract.inbox.InboxGroupPort;
import com.subjex.platform.contract.inbox.InboxEntryView;
import com.subjex.platform.contract.inbox.InboxListQuery;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.inbox.InboxRecipient;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.inbox.InboxSendRequest;
import com.subjex.platform.contract.inbox.InboxSendResult;
import com.subjex.platform.contract.inbox.InboxSeverity;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.storage.StoredObject;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/**
 * InboxService — 操作员站内信用例：收件箱 + 系统/组发送 + 附件暂存/下载.
 * <p>
 * Upload requires {@code inbox.send.system}; GROUP_SEND / ORG_BROADCAST require {@code inbox.broadcast}.
 * Download requires {@code inbox.read} + own entry. Fan-out: sync ≤200; async ≤2000 (IM-A6).
 * 上传需发送权限；组群发需 broadcast；下载需读权限且本人收件；同步≤200 / 异步≤2000。
 */
public final class InboxService {

    /** Synthetic entity key for chunk sessions — 分片会话用的伪实体键. */
    static final String CHUNK_ENTITY_KEY = "inbox";

    private final InboxPort inbox;
    private final InboxGroupPort groups;
    private final InboxOrgBroadcastExpander orgBroadcast;
    private final OperatorTenantAccess tenantAccess;
    private final TenantGuard tenantGuard;
    private final Clock clock;
    private final ObjectStorage objects;
    private final ChunkedBlobUploadStore chunkedUploads;
    private final InboxAttachmentStagingStore staging;
    private final InboxFanoutWorker fanoutWorker;

    public InboxService(
            InboxPort inbox,
            InboxGroupPort groups,
            InboxOrgBroadcastExpander orgBroadcast,
            OperatorTenantAccess tenantAccess,
            TenantGuard tenantGuard,
            Clock clock,
            ObjectStorage objects,
            ChunkedBlobUploadStore chunkedUploads,
            InboxAttachmentStagingStore staging,
            InboxFanoutWorker fanoutWorker) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.orgBroadcast = Objects.requireNonNull(orgBroadcast, "orgBroadcast");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.chunkedUploads = Objects.requireNonNull(chunkedUploads, "chunkedUploads");
        this.staging = Objects.requireNonNull(staging, "staging");
        this.fanoutWorker = Objects.requireNonNull(fanoutWorker, "fanoutWorker");
    }

    /** List own OPERATOR entries — 列本人操作员收件. */
    public List<InboxEntryView> listOwn(
            OperatorPrincipal operator,
            String tenantId,
            boolean unreadOnly,
            InboxChannel channel,
            int limit,
            int offset) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_READ.permissionName());
        return inbox.listEntries(
                tid,
                InboxRecipientRealm.OPERATOR,
                operator.subjectId(),
                new InboxListQuery(unreadOnly, channel, limit, offset));
    }

    /** Unread badge count — 未读角标计数. */
    public long unreadCount(OperatorPrincipal operator, String tenantId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_READ.permissionName());
        return inbox.unreadCount(tid, InboxRecipientRealm.OPERATOR, operator.subjectId());
    }

    /** Own entry detail — 本人详情. */
    public InboxEntryView getOwn(OperatorPrincipal operator, String tenantId, String entryId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_READ.permissionName());
        return requireOwnEntry(operator, tid, entryId);
    }

    /** Mark one own entry read (idempotent) — 标记本人一条已读（幂等）. */
    public void markRead(OperatorPrincipal operator, String tenantId, String entryId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_READ.permissionName());
        requireOwnEntry(operator, tid, entryId);
        inbox.markRead(
                tid, InboxRecipientRealm.OPERATOR, operator.subjectId(), entryId, clock.instant());
    }

    /**
     * Mark many own entries read; any foreign → 403, any missing → 404.
     * 批量已读；任一条属他人 → 403，任一条不存在 → 404。
     */
    public int markReadBatch(OperatorPrincipal operator, String tenantId, List<String> entryIds) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_READ.permissionName());
        if (entryIds == null || entryIds.isEmpty()) {
            throw new IllegalArgumentException("entryIds must not be empty");
        }
        Instant at = clock.instant();
        int marked = 0;
        for (String raw : entryIds) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("entryIds must not contain blank ids");
            }
            String entryId = raw.trim();
            requireOwnEntry(operator, tid, entryId);
            if (inbox.markRead(tid, InboxRecipientRealm.OPERATOR, operator.subjectId(), entryId, at)) {
                marked++;
            }
        }
        return marked;
    }

    /**
     * Send SYSTEM notice; optional {@code attachmentStagingIds} bind completed uploads.
     * 发 SYSTEM 通知；可选暂存附件 id 绑定已上传文件。
     */
    public InboxSendResult sendSystem(
            OperatorPrincipal operator,
            String tenantId,
            String title,
            String body,
            InboxSeverity severity,
            List<String> recipientSubjectIds,
            String deepLink,
            List<String> attachmentStagingIds) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_SEND_SYSTEM.permissionName());
        if (recipientSubjectIds == null || recipientSubjectIds.isEmpty()) {
            throw new IllegalArgumentException("recipientSubjectIds must not be empty");
        }
        InboxSeverity sev = severity == null ? InboxSeverity.INFO : severity;
        List<InboxRecipient> recipients = new ArrayList<>(recipientSubjectIds.size());
        for (String subjectId : recipientSubjectIds) {
            if (subjectId == null || subjectId.isBlank()) {
                throw new IllegalArgumentException("recipientSubjectIds must not contain blank ids");
            }
            recipients.add(new InboxRecipient(InboxRecipientRealm.OPERATOR, subjectId.trim()));
        }
        List<InboxAttachmentBind> binds = takeStagingBinds(tid, operator.subjectId(), attachmentStagingIds);
        return inbox.send(new InboxSendRequest(
                tid,
                InboxChannel.SYSTEM,
                title,
                body,
                sev,
                "system",
                operator.subjectId(),
                null,
                null,
                null,
                null,
                blankToNull(deepLink),
                recipients,
                binds,
                clock.instant()));
    }

    /**
     * GROUP_SEND: expand group members; sync ≤200 or async ≤2000 (ADR 0009); reject above hard cap.
     * Empty group → 400. Optional staging attachments.
     * 组群发：展开成员；同步≤200 / 异步≤2000；超硬顶拒绝；空组 400；可选暂存附件。
     */
    public InboxSendResult sendToGroup(
            OperatorPrincipal operator,
            String tenantId,
            String groupId,
            String title,
            String body,
            InboxSeverity severity,
            String deepLink,
            List<String> attachmentStagingIds) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_BROADCAST.permissionName());
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId required");
        }
        String gid = groupId.trim();
        InboxGroup group = groups.get(tid, gid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "inbox group not found"));
        List<InboxRecipient> recipients = groups.expandMembers(tid, gid);
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("group has no members");
        }
        InboxSeverity sev = severity == null ? InboxSeverity.INFO : severity;
        List<InboxAttachmentBind> binds = takeStagingBinds(tid, operator.subjectId(), attachmentStagingIds);
        return dispatchFanout(new InboxSendRequest(
                tid,
                InboxChannel.GROUP_SEND,
                title,
                body,
                sev,
                "group",
                operator.subjectId(),
                "inbox_group",
                group.groupId(),
                null,
                null,
                blankToNull(deepLink),
                recipients,
                binds,
                clock.instant()));
    }


    /**
     * ORG_BROADCAST: expand org memberships ∩ sender OrgScope; sync ≤200 or async ≤2000; reject N&gt;2000.
     * Out-of-scope org → 403; unknown/unlinked org → 404; empty members → 400.
     * 组织广播：成员 ∩ 发送者 OrgScope；同步/异步限额；越权 403；未关联 404；空成员 400。
     */
    public InboxSendResult sendOrgBroadcast(
            OperatorPrincipal operator,
            String tenantId,
            List<String> orgIds,
            boolean includeDescendants,
            String title,
            String body,
            InboxSeverity severity,
            String deepLink,
            List<String> attachmentStagingIds) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_BROADCAST.permissionName());
        List<InboxRecipient> recipients =
                orgBroadcast.expand(tid, operator.subjectId(), orgIds, includeDescendants);
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("no ACTIVE memberships under target organizations");
        }
        InboxSeverity sev = severity == null ? InboxSeverity.INFO : severity;
        List<InboxAttachmentBind> binds = takeStagingBinds(tid, operator.subjectId(), attachmentStagingIds);
        String refId = orgIds == null || orgIds.isEmpty()
                ? null
                : orgIds.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).findFirst().orElse(null);
        return dispatchFanout(new InboxSendRequest(
                tid,
                InboxChannel.ORG_BROADCAST,
                title,
                body,
                sev,
                "org",
                operator.subjectId(),
                "organization",
                refId,
                null,
                null,
                blankToNull(deepLink),
                recipients,
                binds,
                clock.instant()));
    }

    /**
     * Direct single-shot upload (small files) → staging id — 单次直传（小文件）→ 暂存 id.
     */
    public InboxAttachmentStaging uploadDirect(
            OperatorPrincipal operator,
            String tenantId,
            String fileName,
            String contentType,
            byte[] body) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_SEND_SYSTEM.permissionName());
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("empty attachment body");
        }
        if (body.length > InboxAttachmentBind.MAX_BYTES) {
            throw new IllegalArgumentException("content exceeds maxBytes=" + InboxAttachmentBind.MAX_BYTES);
        }
        String type = InboxAttachmentContentTypes.requireAllowed(contentType);
        String name = requireFileName(fileName);
        String stagingKey = storageKeyForNewStaging();
        String checksum = sha256Hex(body);
        objects.put(tid, stagingKey, body, type);
        return staging.put(tid, operator.subjectId(), name, type, body.length, stagingKey, checksum);
    }

    /** Start chunked upload session — 开启分片上传会话. */
    public ChunkedBlobUploadSession initChunkedUpload(
            OperatorPrincipal operator, String tenantId, String fileName, String contentType, long totalBytes) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_SEND_SYSTEM.permissionName());
        String type = InboxAttachmentContentTypes.requireAllowed(contentType);
        requireFileName(fileName);
        String fieldToken = toFieldToken(fileName);
        return chunkedUploads.init(
                tid,
                CHUNK_ENTITY_KEY,
                operator.subjectId(),
                fieldToken,
                type,
                totalBytes,
                InboxAttachmentBind.MAX_BYTES);
    }

    /** Put one chunk — 写入一分片. */
    public ChunkedBlobUploadSession putChunk(
            OperatorPrincipal operator, String tenantId, String uploadId, int chunkIndex, byte[] data) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_SEND_SYSTEM.permissionName());
        requireOwnedChunkSession(tid, operator.subjectId(), uploadId);
        return chunkedUploads.putChunk(uploadId, chunkIndex, data == null ? new byte[0] : data);
    }

    /**
     * Assemble chunks → ObjectStorage → staging registry — 组装分片并登记暂存.
     */
    public InboxAttachmentStaging completeChunkedUpload(
            OperatorPrincipal operator, String tenantId, String uploadId, String fileNameOrNull) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_SEND_SYSTEM.permissionName());
        ChunkedBlobUploadSession session = requireOwnedChunkSession(tid, operator.subjectId(), uploadId);
        String type = InboxAttachmentContentTypes.requireAllowed(session.contentType());
        String name = fileNameOrNull == null || fileNameOrNull.isBlank()
                ? fromFieldToken(session.fieldName())
                : requireFileName(fileNameOrNull);
        byte[] assembled = chunkedUploads.assemble(uploadId);
        try {
            if (assembled.length == 0) {
                throw new IllegalArgumentException("empty attachment body");
            }
            if (assembled.length > InboxAttachmentBind.MAX_BYTES) {
                throw new IllegalArgumentException("content exceeds maxBytes=" + InboxAttachmentBind.MAX_BYTES);
            }
            String stagingKey = storageKeyForNewStaging();
            String checksum = sha256Hex(assembled);
            objects.put(tid, stagingKey, assembled, type);
            return staging.put(
                    tid, operator.subjectId(), name, type, assembled.length, stagingKey, checksum);
        } finally {
            chunkedUploads.abort(uploadId);
        }
    }

    /** Abort chunk session — 中止分片会话. */
    public void abortChunkedUpload(OperatorPrincipal operator, String tenantId, String uploadId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_SEND_SYSTEM.permissionName());
        requireOwnedChunkSession(tid, operator.subjectId(), uploadId);
        chunkedUploads.abort(uploadId);
    }

    /** List attachments on own entry — 列本人收件上的附件. */
    public List<InboxAttachment> listAttachments(OperatorPrincipal operator, String tenantId, String entryId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_READ.permissionName());
        requireOwnEntry(operator, tid, entryId);
        return inbox.listAttachmentsForOwnEntry(
                tid, InboxRecipientRealm.OPERATOR, operator.subjectId(), entryId);
    }

    /**
     * Download attachment bytes for own entry only — 仅本人收件可下载附件字节.
     */
    public DownloadedAttachment downloadAttachment(
            OperatorPrincipal operator, String tenantId, String entryId, String attachmentId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_READ.permissionName());
        requireOwnEntry(operator, tid, entryId);
        InboxAttachment meta = inbox.findAttachmentForOwnEntry(
                        tid, InboxRecipientRealm.OPERATOR, operator.subjectId(), entryId, attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "attachment not found"));
        StoredObject stored = objects
                .find(tid, meta.storageKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "attachment bytes missing"));
        return new DownloadedAttachment(meta, stored.content());
    }

    /** DownloadedAttachment — 元数据 + 字节. */
    public record DownloadedAttachment(InboxAttachment metadata, byte[] content) {
        public DownloadedAttachment {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(content, "content");
        }
    }


    /**
     * Route by unique recipient count: sync ≤200, deferred ≤2000, else reject.
     * 按去重收件人数路由：同步 / 异步 / 拒绝。
     */
    private InboxSendResult dispatchFanout(InboxSendRequest request) {
        int n = request.recipients().size();
        // Port dedupes again; pre-check uses list size (expanders already unique).
        if (n > InboxSendRequest.HARD_FANOUT_MAX) {
            throw new IllegalArgumentException(
                    "recipientCount="
                            + n
                            + " exceeds hardFanoutMax="
                            + InboxSendRequest.HARD_FANOUT_MAX);
        }
        if (n > InboxSendRequest.SYNC_FANOUT_MAX) {
            InboxSendResult deferred = inbox.sendDeferred(request);
            fanoutWorker.schedule(deferred.fanoutJobId());
            return deferred;
        }
        return inbox.send(request);
    }

    private List<InboxAttachmentBind> takeStagingBinds(
            String tenantId, String uploaderSubjectId, List<String> stagingIds) {
        if (stagingIds == null || stagingIds.isEmpty()) {
            return List.of();
        }
        if (stagingIds.size() > InboxAttachmentBind.MAX_PER_MESSAGE) {
            throw new IllegalArgumentException(
                    "attachments exceed maxPerMessage=" + InboxAttachmentBind.MAX_PER_MESSAGE);
        }
        List<InboxAttachmentBind> binds = new ArrayList<>(stagingIds.size());
        int order = 0;
        for (String raw : stagingIds) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("attachmentStagingIds must not contain blank ids");
            }
            InboxAttachmentStaging taken = staging
                    .takeOwned(tenantId, uploaderSubjectId, raw.trim())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "unknown or foreign attachmentStagingId: " + raw.trim()));
            binds.add(new InboxAttachmentBind(
                    taken.fileName(),
                    taken.contentType(),
                    taken.byteSize(),
                    taken.storageKey(),
                    taken.checksumSha256(),
                    order++));
        }
        return binds;
    }

    private ChunkedBlobUploadSession requireOwnedChunkSession(
            String tenantId, String subjectId, String uploadId) {
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

    private InboxEntryView requireOwnEntry(OperatorPrincipal operator, String tenantId, String entryId) {
        InboxEntryView view = inbox.findEntryInTenant(tenantId, entryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "inbox entry not found"));
        if (view.entry().recipientRealm() != InboxRecipientRealm.OPERATOR
                || !view.entry().recipientSubjectId().equals(operator.subjectId())) {
            throw new AccessDeniedException("inbox entry belongs to another recipient");
        }
        return view;
    }

    private String requireTenantAndGrant(OperatorPrincipal operator, String tenantId) {
        Objects.requireNonNull(operator, "operator");
        tenantGuard.requireTenant(tenantId);
        String tid = tenantId.trim();
        tenantAccess.requireGranted(operator, tid);
        return tid;
    }

    private static void requirePermission(OperatorPrincipal operator, String permission) {
        if (!operator.permissionNames().contains(permission)) {
            throw new AccessDeniedException("missing permission " + permission);
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String requireFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName required");
        }
        String name = fileName.trim();
        if (name.length() > 512) {
            throw new IllegalArgumentException("fileName too long");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("fileName must not contain path segments");
        }
        return name;
    }

    private static String storageKeyForNewStaging() {
        return "inbox-staging-" + UUID.randomUUID();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Map file name to CHUNK fieldName token — 文件名映射为分片 fieldName 标识. */
    static String toFieldToken(String fileName) {
        String base = fileName.trim().replaceAll("[^a-zA-Z0-9_]", "_");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
            base = "f_" + base;
        }
        if (base.length() > 128) {
            base = base.substring(0, 128);
        }
        return base;
    }

    static String fromFieldToken(String fieldToken) {
        return fieldToken == null || fieldToken.isBlank() ? "attachment.bin" : fieldToken;
    }
}
