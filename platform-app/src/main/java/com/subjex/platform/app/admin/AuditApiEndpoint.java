package com.subjex.platform.app.admin;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.jdbc.JdbcAdminReader;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AuditApiEndpoint — 审计记录接口：{@link AuditPage} 的 JSON 孪生，同样的行、同样的白话动作和结果。
 * <p>
 * Newest first, at most {@link AuditPage#ROW_LIMIT} rows. Each entry keeps the raw names and adds the
 * plain zh/en words the page shows, so the two cannot disagree.
 * 最新的在前，最多 {@link AuditPage#ROW_LIMIT} 行。每一条保留原始名字，并附上页面显示的中英白话，两边不会各说各的。
 */
@RestController
public class AuditApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/audit";

    private final JdbcAdminReader adminReader;

    public AuditApiEndpoint(JdbcAdminReader adminReader) {
        this.adminReader = adminReader;
    }

    @GetMapping(PATH)
    public AuditDocument audit() {
        List<AuditEntryDocument> entries = adminReader.auditEntries(AuditPage.ROW_LIMIT).stream()
                .map(AuditApiEndpoint::entry)
                .toList();
        return new AuditDocument(entries);
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
                outcome[1]);
    }

    /**
     * AuditDocument — 审计记录文档：最新在前的条目。
     */
    public record AuditDocument(List<AuditEntryDocument> entries) {}

    /**
     * AuditEntryDocument — 一条审计：原始名字加页面上的中英白话；{@code actor} 是登录名，没有就是身份标识。
     */
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
            String outcomeWordEn) {}
}
