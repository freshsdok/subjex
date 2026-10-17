package com.subjex.platform.app.inbox;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxEntryView;
import com.subjex.platform.contract.inbox.InboxListQuery;
import com.subjex.platform.contract.inbox.InboxSendResult;
import com.subjex.platform.contract.inbox.InboxSeverity;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * InboxEndpoint — IM-2 REST：操作员站内信列表 / 未读 / 已读 / 系统发送。
 * <p>
 * Paths under {@code /api/v1/inbox/**}; tenant via {@code X-Tenant-Id} or {@code tenantId}.
 * 路径在 {@code /api/v1/inbox/**}；租户经 {@code X-Tenant-Id} 或 {@code tenantId}。
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
                body.deepLink());
        return SendDocument.from(sent);
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
            String title, String body, String severity, List<String> recipientSubjectIds, String deepLink) {}

    public record UnreadCountDocument(long count) {}

    public record MarkReadDocument(int marked) {}

    public record EntriesDocument(List<EntryDocument> entries) {}

    public record SendDocument(
            String messageId, String tenantId, String channel, String title, List<String> entryIds) {
        static SendDocument from(InboxSendResult result) {
            return new SendDocument(
                    result.message().messageId(),
                    result.message().tenantId(),
                    result.message().channel().name(),
                    result.message().title(),
                    result.entries().stream().map(e -> e.entryId()).toList());
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
