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
 * DeclarationPromoteEndpoint — 声明晋升 HTTP：双人确认后晋升、回滚上一份已晋升、历史（P7）。
 * <p>
 * POST approve → POST promote (different operator) → optional POST rollback.
 * Rollback never auto-DROP columns; unfinished migrations still block via {@link DeclarationPromoteBlockedByMigration}.
 * 先确认再晋升（须另一操作员）；回滚不自动删列；未完成迁移仍阻断晋升。
 */
@RestController
public class DeclarationPromoteEndpoint {

    public static final String PATH = JsonApi.BASE + "/declarations";

    private final DeclarationPromoteService promoteService;
    private final JdbcDeclarationStore store;
    private final JdbcDeclarationPromoteApprovalStore approvals;
    private final OperatorTenantAccess tenantAccess;
    private final OperatorActionAudit audit;

    public DeclarationPromoteEndpoint(
            DeclarationPromoteService promoteService,
            JdbcDeclarationStore store,
            JdbcDeclarationPromoteApprovalStore approvals,
            OperatorTenantAccess tenantAccess,
            OperatorActionAudit audit) {
        this.promoteService = Objects.requireNonNull(promoteService, "promoteService");
        this.store = Objects.requireNonNull(store, "store");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** First operator requests promote approval (P7) — 第一操作员请求晋升确认（P7）。 */
    @PostMapping(PATH + "/{kind}/{key}/promote/approvals")
    public ApprovalDocument requestApproval(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody(required = false) PromoteRequest body) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        int revisionNumber;
        if (body == null || body.revision() == null) {
            revisionNumber = store.latest(tid, k, key)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
                    .revision();
        } else {
            store.findRevision(tid, k, key, body.revision())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            revisionNumber = body.revision();
        }
        DeclarationPromoteApproval row =
                approvals.request(tid, k, key, revisionNumber, operator.subjectId());
        audit.record(
                operator,
                "declaration.promote.approve",
                k.wireName() + "/" + key + "@" + revisionNumber,
                AuditOutcome.ALLOWED);
        return new ApprovalDocument(
                row.approvalId(),
                row.tenantId(),
                row.kind().wireName(),
                row.declarationKey(),
                row.revision(),
                row.requestedBySubjectId(),
                row.approvalState(),
                row.createdAt());
    }

    /**
     * Second operator promotes after consumed approval; migrations must be settled -
     * 第二操作员在已消费确认后晋升；迁移须已结清。
     */
    @PostMapping(PATH + "/{kind}/{key}/promote")
    public PromoteDocument promote(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody(required = false) PromoteRequest body) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        if (body == null || body.approvalId() == null || body.approvalId().isBlank()) {
            throw new DeclarationPromoteNeedsSecondOperator(
                    "approvalId required: POST .../promote/approvals first, then a second operator promotes");
        }
        Integer revisionNumber = body.revision();
        DeclarationPromoteService.DeclarationPromoteResult result;
        if (revisionNumber == null) {
            store.latest(tid, k, key)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            result = promoteService.promoteLatest(tid, k, key, operator.subjectId(), body.approvalId());
        } else {
            store.findRevision(tid, k, key, revisionNumber)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            result = promoteService.promoteRevision(
                    tid, k, key, revisionNumber, operator.subjectId(), body.approvalId());
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

    /** Rollback to previous PROMOTED (never auto-DROP) — 回滚到上一份已晋升（绝不自动 DROP）。 */
    @PostMapping(PATH + "/{kind}/{key}/rollback")
    public PromoteDocument rollback(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        DeclarationPromoteService.DeclarationPromoteResult result =
                promoteService.rollback(tid, k, key, operator.subjectId());
        String target = result.kind().wireName() + "/" + result.declarationKey() + "@" + result.revision();
        audit.record(operator, "declaration.rollback", target, AuditOutcome.ALLOWED);
        return new PromoteDocument(
                result.tenantId(),
                result.kind().wireName(),
                result.declarationKey(),
                result.revision(),
                result.gitCommitSha(),
                JdbcDeclarationStore.PROMOTED_STATE);
    }

    /** Promote audit history — 晋升审计历史。 */
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

    /** Promote / approval body — 晋升或确认正文。 */
    public record PromoteRequest(Integer revision, String approvalId) {}

    public record ApprovalDocument(
            String approvalId,
            String tenantId,
            String declarationKind,
            String declarationKey,
            int revision,
            String requestedBySubjectId,
            String approvalState,
            Instant createdAt) {}

    public record PromoteDocument(
            String tenantId,
            String declarationKind,
            String declarationKey,
            int revision,
            String gitCommitSha,
            String draftState) {}

    public record PromotesDocument(List<PromoteHistoryDocument> promotes) {}

    public record PromoteHistoryDocument(
            String tenantId,
            String declarationKind,
            String declarationKey,
            int revision,
            String gitCommitSha,
            Instant promotedAt,
            String promotedBySubjectId) {}
}
