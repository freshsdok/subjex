package com.subjex.platform.app.inbox;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxEntryView;
import com.subjex.platform.contract.inbox.InboxListQuery;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * EndUserInboxEndpoint — IM-6 thin END_USER mailbox for uni-app (read + mark-read).
 * <p>
 * Same AuthZ as operator inbox ({@code inbox.read} + tenant grant) but always
 * {@link com.subjex.platform.contract.inbox.InboxRecipientRealm#END_USER}. Does not expose send/compose.
 * 与操作员收件同权限，固定 END_USER 域；不暴露写信。
 */
@RestController
public class EndUserInboxEndpoint {

    /** JSON base for end-user inbox — 终端收件 JSON 根路径. */
    public static final String BASE = JsonApi.BASE + "/me/inbox";

    private final InboxService inbox;

    public EndUserInboxEndpoint(InboxService inbox) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
    }

    @GetMapping(BASE + "/entries")
    public InboxEndpoint.EntriesDocument list(
            @AuthenticationPrincipal OperatorPrincipal principal,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "unreadOnly", required = false, defaultValue = "false") boolean unreadOnly,
            @RequestParam(value = "channel", required = false) String channelRaw,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        requirePrincipal(principal);
        InboxChannel channel = parseChannel(channelRaw);
        int lim = limit == null ? InboxListQuery.DEFAULT_LIMIT : limit;
        int off = offset == null ? 0 : offset;
        List<InboxEndpoint.EntryDocument> entries = inbox
                .listOwnEndUser(principal, resolveTenant(tenantHeader, tenantIdParam), unreadOnly, channel, lim, off)
                .stream()
                .map(InboxEndpoint.EntryDocument::from)
                .toList();
        return new InboxEndpoint.EntriesDocument(entries);
    }

    @GetMapping(BASE + "/unread-count")
    public InboxEndpoint.UnreadCountDocument unreadCount(
            @AuthenticationPrincipal OperatorPrincipal principal,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requirePrincipal(principal);
        long count = inbox.unreadCountEndUser(principal, resolveTenant(tenantHeader, tenantIdParam));
        return new InboxEndpoint.UnreadCountDocument(count);
    }

    @GetMapping(BASE + "/entries/{entryId}")
    public InboxEndpoint.EntryDocument getOne(
            @AuthenticationPrincipal OperatorPrincipal principal,
            @PathVariable("entryId") String entryId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requirePrincipal(principal);
        InboxEntryView view = inbox.getOwnEndUser(principal, resolveTenant(tenantHeader, tenantIdParam), entryId);
        return InboxEndpoint.EntryDocument.from(view);
    }

    @PostMapping(BASE + "/entries/{entryId}/read")
    public InboxEndpoint.MarkReadDocument markOne(
            @AuthenticationPrincipal OperatorPrincipal principal,
            @PathVariable("entryId") String entryId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requirePrincipal(principal);
        inbox.markReadEndUser(principal, resolveTenant(tenantHeader, tenantIdParam), entryId);
        return new InboxEndpoint.MarkReadDocument(1);
    }

    private static void requirePrincipal(OperatorPrincipal principal) {
        if (principal == null) {
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
}
