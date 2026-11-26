package com.subjex.platform.app.inbox;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.identity.enduser.EndUserPrincipals;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.EndUserShellService;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxListQuery;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * EndUserShellInboxEndpoint — own-subject inbox under {@code /api/v1/end-user/inbox}.
 * 终端收件箱（本人+当前租户）。可列出并直接下载本人收件上的附件；不写信，不改 {@code /api/v1/me/inbox}。
 */
@RestController
public class EndUserShellInboxEndpoint {

    /** JSON base for the product end-user inbox — 产品路径上的终端收件根. */
    public static final String BASE = JsonApi.BASE + "/end-user/inbox";

    private final EndUserShellService shell;

    public EndUserShellInboxEndpoint(EndUserShellService shell) {
        this.shell = Objects.requireNonNull(shell, "shell");
    }

    @GetMapping(BASE + "/entries")
    public InboxEndpoint.EntriesDocument list(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "unreadOnly", required = false, defaultValue = "false") boolean unreadOnly,
            @RequestParam(value = "channel", required = false) String channelRaw,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        InboxChannel channel = parseChannel(channelRaw);
        int lim = limit == null ? InboxListQuery.DEFAULT_LIMIT : limit;
        int off = offset == null ? 0 : offset;
        List<InboxEndpoint.EntryDocument> entries = shell
                .listInbox(principal, resolveTenant(tenantHeader, tenantIdParam), unreadOnly, channel, lim, off)
                .stream()
                .map(InboxEndpoint.EntryDocument::from)
                .toList();
        return new InboxEndpoint.EntriesDocument(entries);
    }

    @GetMapping(BASE + "/unread-count")
    public InboxEndpoint.UnreadCountDocument unreadCount(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        long count = shell.unreadCount(principal, resolveTenant(tenantHeader, tenantIdParam));
        return new InboxEndpoint.UnreadCountDocument(count);
    }

    @GetMapping(BASE + "/entries/{entryId}")
    public InboxEndpoint.EntryDocument getOne(
            @PathVariable("entryId") String entryId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        return InboxEndpoint.EntryDocument.from(
                shell.getInbox(principal, resolveTenant(tenantHeader, tenantIdParam), entryId));
    }

    @PostMapping(BASE + "/entries/{entryId}/read")
    public void markRead(
            @PathVariable("entryId") String entryId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        shell.markRead(principal, resolveTenant(tenantHeader, tenantIdParam), entryId);
    }

    /** List attachments on own entry — 列本人收件附件. */
    @GetMapping(BASE + "/entries/{entryId}/attachments")
    public InboxEndpoint.AttachmentsDocument listAttachments(
            @PathVariable("entryId") String entryId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        List<InboxEndpoint.AttachmentDocument> docs = shell
                .listAttachments(principal, resolveTenant(tenantHeader, tenantIdParam), entryId)
                .stream()
                .map(InboxEndpoint.AttachmentDocument::from)
                .toList();
        return new InboxEndpoint.AttachmentsDocument(docs);
    }

    /**
     * Download one attachment as raw bytes (own entry only; not a signed URL).
     * 下载本人收件上的附件字节（不是签名链接）。
     */
    @GetMapping(BASE + "/entries/{entryId}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> downloadAttachment(
            @PathVariable("entryId") String entryId,
            @PathVariable("attachmentId") String attachmentId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        EndUserShellService.DownloadedAttachment downloaded = shell.downloadAttachment(
                principal, resolveTenant(tenantHeader, tenantIdParam), entryId, attachmentId);
        String fileName = downloaded.metadata().fileName().replace("\"", "").replace("\r", "").replace("\n", "");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(downloaded.metadata().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(downloaded.content().length))
                .body(downloaded.content());
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
            return InboxChannel.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown inbox channel", ex);
        }
    }
}
