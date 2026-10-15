package com.subjex.platform.app.admin;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.jdbc.JdbcAdminReader;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AuditApiEndpoint — audit JSON twin with OBS-1 filters (tenant/actor/action/time).
 * 审计 JSON；OBS-1 支持租户/操作者/动作/时间筛选。
 */
@RestController
public class AuditApiEndpoint {

    public static final String PATH = JsonApi.BASE + "/audit";

    private final JdbcAdminReader adminReader;

    public AuditApiEndpoint(JdbcAdminReader adminReader) {
        this.adminReader = adminReader;
    }

    @GetMapping(PATH)
    public AuditDocument audit(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "actor", required = false) String actorLogin,
            @RequestParam(value = "action", required = false) String actionName,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to,
            @RequestParam(value = "limit", required = false) Integer limit) {
        AuditQuery query = new AuditQuery(
                tenantId,
                actorLogin,
                actionName,
                from,
                to,
                limit == null ? AuditQuery.DEFAULT_LIMIT : limit);
        List<AuditEntryDocument> entries = adminReader.auditEntries(query).stream()
                .map(AuditApiEndpoint::entry)
                .toList();
        return new AuditDocument(entries, query);
    }

    static AuditEntryDocument entry(AuditRow row) {
        String[] action = AuditPage.plainPair(AuditPage.ACTION_WORDS, row.actionName());
        String[] outcome = AuditPage.plainPair(AuditPage.OUTCOME_WORDS, row.outcome());
        return new AuditEntryDocument(
                row.auditEntryId(),
                row.occurredAt(),
                row.tenantId(),
                row.actorIdentityId(),
                row.actorLogin(),
                AuditPage.actor(row),
                row.actionName(),
                action[0],
                action[1],
                row.actionTarget(),
                row.outcome(),
                outcome[0],
                outcome[1],
                row.entityKey(),
                row.declarationVersion(),
                row.resolutionSource());
    }

    public record AuditDocument(List<AuditEntryDocument> entries, AuditQuery appliedFilters) {}

    public record AuditEntryDocument(
            String auditEntryId,
            Instant occurredAt,
            String tenantId,
            String actorIdentityId,
            String actorLogin,
            String actor,
            String actionName,
            String actionWordZh,
            String actionWordEn,
            String actionTarget,
            String outcome,
            String outcomeWordZh,
            String outcomeWordEn,
            String entityKey,
            Integer declarationVersion,
            String resolutionSource) {}
}
