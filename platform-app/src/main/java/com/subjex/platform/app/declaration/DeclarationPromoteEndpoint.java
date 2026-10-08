package com.subjex.platform.app.declaration;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * DeclarationPromoteEndpoint — 声明晋升 HTTP：POST 晋升进内部 git、GET 晋升历史；操作员审计。
 * <p>
 * Local commit only (no GitHub push). POST needs {@code declaration.promote}; GET needs
 * {@code declaration.read}. Tenant grant required. Re-promote of {@code PROMOTED} → 409.
 * 仅本地提交（不 push GitHub）。POST 要 {@code declaration.promote}；GET 要 {@code declaration.read}。
 * 须租户授权。已 {@code PROMOTED} 再晋升 → 409。
 */
@RestController
public class DeclarationPromoteEndpoint {

    /** Same JSON base as drafts — 与草稿同一 JSON 根。 */
    public static final String PATH = JsonApi.BASE + "/declarations";

    private final DeclarationPromoteService promoteService;
    private final JdbcDeclarationStore store;
    private final OperatorTenantAccess tenantAccess;
    private final OperatorActionAudit audit;

    public DeclarationPromoteEndpoint(
            DeclarationPromoteService promoteService,
            JdbcDeclarationStore store,
            OperatorTenantAccess tenantAccess,
            OperatorActionAudit audit) {
        this.promoteService = Objects.requireNonNull(promoteService, "promoteService");
        this.store = Objects.requireNonNull(store, "store");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @PostMapping(PATH + "/{kind}/{key}/promote")
    public PromoteDocument promote(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody(required = false) PromoteRequest body) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        Integer revisionNumber = body == null ? null : body.revision();
        DeclarationPromoteService.DeclarationPromoteResult result;
        if (revisionNumber == null) {
            store.latest(tid, k, key)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            result = promoteService.promoteLatest(tid, k, key, operator.subjectId());
        } else {
            store.findRevision(tid, k, key, revisionNumber)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            result = promoteService.promoteRevision(tid, k, key, revisionNumber, operator.subjectId());
        }
        String target = result.kind().wireName() + "/" + result.declarationKey() + "@" + result.revision();
        audit.record(operator, "declaration.promote", target, AuditOutcome.ALLOWED);
        return new PromoteDocument(
                result.tenantId(),
                result.kind().wireName(),
                result.declarationKey(),
                result.revision(),
                result.gitCommitSha(),
                JdbcDeclarationStore.PROMOTED_STATE);
    }

    @GetMapping(PATH + "/{kind}/{key}/promotes")
    public PromotesDocument listPromotes(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        List<PromoteHistoryDocument> promotes =
                store.listPromotes(tid, k, key).stream().map(DeclarationPromoteEndpoint::history).toList();
        return new PromotesDocument(promotes);
    }

    private String requireTenant(OperatorPrincipal operator, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId required");
        }
        String tid = tenantId.trim();
        tenantAccess.requireGranted(operator, tid);
        return tid;
    }

    private static PromoteHistoryDocument history(DeclarationPromote row) {
        return new PromoteHistoryDocument(
                row.tenantId(),
                row.kind().wireName(),
                row.declarationKey(),
                row.revision(),
                row.gitCommitSha(),
                row.promotedAt(),
                row.promotedBySubjectId());
    }

    /** Optional promote body — 可选晋升正文（省略则晋升最新修订）。 */
    public record PromoteRequest(Integer revision) {}

    /** PromoteDocument — 一次晋升结果。 */
    public record PromoteDocument(
            String tenantId,
            String declarationKind,
            String declarationKey,
            int revision,
            String gitCommitSha,
            String draftState) {}

    /** PromotesDocument — 晋升历史列表。 */
    public record PromotesDocument(List<PromoteHistoryDocument> promotes) {}

    /** PromoteHistoryDocument — 一条晋升历史。 */
    public record PromoteHistoryDocument(
            String tenantId,
            String declarationKind,
            String declarationKey,
            int revision,
            String gitCommitSha,
            Instant promotedAt,
            String promotedBySubjectId) {}
}
