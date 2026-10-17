package com.subjex.platform.app.inbox;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.entity.ChunkedBlobUploadSession;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.inbox.InboxAttachment;
import com.subjex.platform.contract.inbox.InboxAttachmentBind;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxEntryView;
import com.subjex.platform.contract.inbox.InboxListQuery;
import com.subjex.platform.contract.inbox.InboxSendResult;
import com.subjex.platform.contract.inbox.InboxSeverity;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * InboxEndpoint — 操作员站内信 REST：收件箱 / 系统发送 / IM-A2 附件上传与下载.
 * <p>
 * Paths under {@code /api/v1/inbox/**}; tenant via {@code X-Tenant-Id} or {@code tenantId}.
 * Download is own-entry only (ADR 0009 locked #5).
 * 路径在 {@code /api/v1/inbox/**}；下载仅本人收件。
 */
@RestController
public class InboxEndpoint {

    public static final String BASE = JsonApi.BASE + "/inbox";

    private final InboxService inbox;

    public InboxEndpoint(InboxService inbox) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
    }

    @GetMapping(BASE + "/entries")
    public EntriesDocument list(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "unreadOnly", required = false, defaultValue = "false") boolean unreadOnly,
            @RequestParam(value = "channel", required = false) String channelRaw,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        requireOperator(operator);
        InboxChannel channel = parseChannel(channelRaw);
        int lim = limit == null ? InboxListQuery.DEFAULT_LIMIT : limit;
        int off = offset == null ? 0 : offset;
        List<EntryDocument> entries = inbox
                .listOwn(operator, resolveTenant(tenantHeader, tenantIdParam), unreadOnly, channel, lim, off)
                .stream()
                .map(EntryDocument::from)
                .toList();
        return new EntriesDocument(entries);
    }

    @GetMapping(BASE + "/unread-count")
    public UnreadCountDocument unreadCount(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        long count = inbox.unreadCount(operator, resolveTenant(tenantHeader, tenantIdParam));
        return new UnreadCountDocument(count);
    }

    @GetMapping(BASE + "/entries/{entryId}")
    public EntryDocument getOne(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("entryId") String entryId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        return EntryDocument.from(inbox.getOwn(operator, resolveTenant(tenantHeader, tenantIdParam), entryId));
    }

    @PostMapping(BASE + "/entries/{entryId}/read")
    public MarkReadDocument markOne(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("entryId") String entryId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        inbox.markRead(operator, resolveTenant(tenantHeader, tenantIdParam), entryId);
        return new MarkReadDocument(1);
    }

    @PostMapping(BASE + "/entries/read")
    public MarkReadDocument markBatch(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody BatchReadRequest body) {
        requireOperator(operator);
        if (body == null || body.entryIds() == null) {
            throw new IllegalArgumentException("entryIds required");
        }
        int marked = inbox.markReadBatch(operator, resolveTenant(tenantHeader, tenantIdParam), body.entryIds());
        return new MarkReadDocument(marked);
    }

    @PostMapping(BASE + "/system")
    public SendDocument sendSystem(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody SystemSendRequest body) {
        requireOperator(operator);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        InboxSendResult sent = inbox.sendSystem(
                operator,
                resolveTenant(tenantHeader, tenantIdParam),
                body.title(),
                body.body(),
                parseSeverity(body.severity()),
                body.recipientSubjectIds(),
                body.deepLink(),
                body.attachmentStagingIds());
        return SendDocument.from(sent);
    }

    @PostMapping(BASE + "/group-send")
    public SendDocument sendToGroup(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody GroupSendRequest body) {
        requireOperator(operator);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        InboxSendResult sent = inbox.sendToGroup(
                operator,
                resolveTenant(tenantHeader, tenantIdParam),
                body.groupId(),
                body.title(),
                body.body(),
                parseSeverity(body.severity()),
                body.deepLink(),
                body.attachmentStagingIds());
        return SendDocument.from(sent);
    }

    @PostMapping(BASE + "/broadcast")
    public SendDocument sendOrgBroadcast(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody OrgBroadcastRequest body) {
        requireOperator(operator);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        boolean includeDescendants = body.includeDescendants() == null || body.includeDescendants();
        InboxSendResult sent = inbox.sendOrgBroadcast(
                operator,
                resolveTenant(tenantHeader, tenantIdParam),
                body.orgIds(),
                includeDescendants,
                body.title(),
                body.body(),
                parseSeverity(body.severity()),
                body.deepLink(),
                body.attachmentStagingIds());
        return SendDocument.from(sent);
    }

    /**
     * Direct upload (octet-stream) → staging — 直传字节流 → 暂存.
     */
    @PostMapping(value = BASE + "/attachments", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public StagingDocument uploadDirect(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam("fileName") String fileName,
            @RequestParam("contentType") String contentType,
            @RequestBody(required = false) byte[] body) {
        requireOperator(operator);
        InboxAttachmentStaging staging =
                inbox.uploadDirect(
                        operator, resolveTenant(tenantHeader, tenantIdParam), fileName, contentType, body);
        return StagingDocument.from(staging);
    }

    /** Init chunked upload — 开启分片上传. */
    @PostMapping(value = BASE + "/attachments/uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UploadInitDocument initUpload(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody UploadInitRequest body) {
        requireOperator(operator);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        ChunkedBlobUploadSession session = inbox.initChunkedUpload(
                operator,
                resolveTenant(tenantHeader, tenantIdParam),
                body.fileName(),
                body.contentType(),
                body.totalBytes());
        return UploadInitDocument.from(session);
    }

    /** Put chunk — 写入分片. */
    @PutMapping(
            value = BASE + "/attachments/uploads/{uploadId}/chunks/{chunkIndex}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ChunkAckDocument putChunk(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("uploadId") String uploadId,
            @PathVariable("chunkIndex") int chunkIndex,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody(required = false) byte[] body) {
        requireOperator(operator);
        ChunkedBlobUploadSession updated = inbox.putChunk(
                operator, resolveTenant(tenantHeader, tenantIdParam), uploadId, chunkIndex, body);
        return new ChunkAckDocument(
                updated.uploadId(), chunkIndex, updated.receivedIndices().size(), updated.chunkCount());
    }

    /** Complete chunked upload → staging — 完成分片 → 暂存. */
    @PostMapping(BASE + "/attachments/uploads/{uploadId}/complete")
    public StagingDocument completeUpload(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("uploadId") String uploadId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody(required = false) CompleteUploadRequest body) {
        requireOperator(operator);
        String fileName = body == null ? null : body.fileName();
        InboxAttachmentStaging staging =
                inbox.completeChunkedUpload(operator, resolveTenant(tenantHeader, tenantIdParam), uploadId, fileName);
        return StagingDocument.from(staging);
    }

    /** Abort chunked upload — 中止分片上传. */
    @DeleteMapping(BASE + "/attachments/uploads/{uploadId}")
    public ResponseEntity<Void> abortUpload(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("uploadId") String uploadId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        inbox.abortChunkedUpload(operator, resolveTenant(tenantHeader, tenantIdParam), uploadId);
        return ResponseEntity.noContent().build();
    }

    /** List attachments on own entry — 列本人收件附件. */
    @GetMapping(BASE + "/entries/{entryId}/attachments")
    public AttachmentsDocument listAttachments(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("entryId") String entryId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        List<AttachmentDocument> docs = inbox
                .listAttachments(operator, resolveTenant(tenantHeader, tenantIdParam), entryId)
                .stream()
                .map(AttachmentDocument::from)
                .toList();
        return new AttachmentsDocument(docs);
    }

    /** Download one attachment (own entry only) — 下载附件（仅本人）. */
    @GetMapping(BASE + "/entries/{entryId}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> downloadAttachment(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("entryId") String entryId,
            @PathVariable("attachmentId") String attachmentId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        InboxService.DownloadedAttachment downloaded = inbox.downloadAttachment(
                operator, resolveTenant(tenantHeader, tenantIdParam), entryId, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(downloaded.metadata().contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + downloaded.metadata().fileName().replace("\"", "") + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(downloaded.content().length))
                .body(downloaded.content());
    }

    private static void requireOperator(OperatorPrincipal operator) {
        if (operator == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
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

    private static InboxChannel parseChannel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return InboxChannel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown channel " + raw);
        }
    }

    private static InboxSeverity parseSeverity(String raw) {
        if (raw == null || raw.isBlank()) {
            return InboxSeverity.INFO;
        }
        try {
            return InboxSeverity.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown severity " + raw);
        }
    }

    public record BatchReadRequest(List<String> entryIds) {}

    public record SystemSendRequest(
            String title,
            String body,
            String severity,
            List<String> recipientSubjectIds,
            String deepLink,
            List<String> attachmentStagingIds) {}

    public record GroupSendRequest(
            String groupId,
            String title,
            String body,
            String severity,
            String deepLink,
            List<String> attachmentStagingIds) {}

    public record OrgBroadcastRequest(
            List<String> orgIds,
            Boolean includeDescendants,
            String title,
            String body,
            String severity,
            String deepLink,
            List<String> attachmentStagingIds) {}

    public record UploadInitRequest(String fileName, String contentType, long totalBytes) {}

    public record CompleteUploadRequest(String fileName) {}

    public record UnreadCountDocument(long count) {}

    public record MarkReadDocument(int marked) {}

    public record EntriesDocument(List<EntryDocument> entries) {}

    public record AttachmentsDocument(List<AttachmentDocument> attachments) {}

    public record ChunkAckDocument(String uploadId, int chunkIndex, int receivedCount, int chunkCount) {}

    public record UploadInitDocument(
            String uploadId, long totalBytes, int chunkSize, int chunkCount, int maxBytes) {
        static UploadInitDocument from(ChunkedBlobUploadSession session) {
            return new UploadInitDocument(
                    session.uploadId(),
                    session.totalBytes(),
                    session.chunkSize(),
                    session.chunkCount(),
                    InboxAttachmentBind.MAX_BYTES);
        }
    }

    public record StagingDocument(
            String stagingId,
            String fileName,
            String contentType,
            int byteSize,
            String checksumSha256,
            Instant expiresAt) {
        static StagingDocument from(InboxAttachmentStaging staging) {
            return new StagingDocument(
                    staging.stagingId(),
                    staging.fileName(),
                    staging.contentType(),
                    staging.byteSize(),
                    staging.checksumSha256(),
                    staging.expiresAt());
        }
    }

    public record AttachmentDocument(
            String attachmentId,
            String messageId,
            String fileName,
            String contentType,
            int byteSize,
            String checksumSha256,
            int sortOrder) {
        static AttachmentDocument from(InboxAttachment attachment) {
            return new AttachmentDocument(
                    attachment.attachmentId(),
                    attachment.messageId(),
                    attachment.fileName(),
                    attachment.contentType(),
                    attachment.byteSize(),
                    attachment.checksumSha256(),
                    attachment.sortOrder());
        }
    }

    public record SendDocument(
            String messageId,
            String tenantId,
            String channel,
            String title,
            List<String> entryIds,
            List<String> attachmentIds,
            String fanoutStatus,
            String fanoutJobId) {
        static SendDocument from(InboxSendResult result) {
            return new SendDocument(
                    result.message().messageId(),
                    result.message().tenantId(),
                    result.message().channel().name(),
                    result.message().title(),
                    result.entries().stream().map(e -> e.entryId()).toList(),
                    result.attachments().stream().map(a -> a.attachmentId()).toList(),
                    result.fanoutStatus().name(),
                    result.fanoutJobId());
        }
    }

    public record EntryDocument(
            String entryId,
            String messageId,
            String tenantId,
            String channel,
            String title,
            String body,
            String severity,
            String recipientRealm,
            String recipientSubjectId,
            Instant readAt,
            String refKind,
            String refId,
            String deepLink,
            Instant createdAt) {
        static EntryDocument from(InboxEntryView view) {
            return new EntryDocument(
                    view.entry().entryId(),
                    view.message().messageId(),
                    view.entry().tenantId(),
                    view.message().channel().name(),
                    view.message().title(),
                    view.message().body(),
                    view.message().severity().name(),
                    view.entry().recipientRealm().name(),
                    view.entry().recipientSubjectId(),
                    view.entry().readAt(),
                    view.message().refKind(),
                    view.message().refId(),
                    view.message().deepLink(),
                    view.entry().createdAt());
        }
    }
}
