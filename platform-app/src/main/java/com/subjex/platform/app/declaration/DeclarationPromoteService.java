package com.subjex.platform.app.declaration;

import com.subjex.entity.declare.EntityRenderer;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.form.render.FormRenderer;
import com.subjex.form.render.RenderedForm;
import com.subjex.page.declare.PageRenderer;
import com.subjex.page.declare.RenderedFlow;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DeclarationPromoteService — 将租户声明草稿晋升进平台内 git，并落审计行、翻 PROMOTED。
 * <p>
 * Loads a revision (latest or explicit), writes YAML via {@link InternalDeclarationGit},
 * inserts {@code declaration_promote}, then marks the revision {@code PROMOTED}.
 * Does not push. For {@link DeclarationKind#ENTITY}, refuses promote when any migration row for
 * the same tenant+kind+key+revision is not {@code APPLIED} and not {@code CANCELLED}
 * (PENDING/REVIEWED/FAILED block; no rows = metadata-only, allowed). Form unchanged.
 * For {@link DeclarationKind#FLOW}, {@link DeclarationRuntimePages#ensureAfterFlowPromote(String)}
 * fail-closes unless list/new/detail paths match {@code /pages/{key}} (and tenantScoped flows
 * carry the four business-table blocks). Pages are not written as separate YAML — runtime reads
 * the promoted flow via {@link EffectiveDeclarationService#effectiveFlow}.
 * Re-promoting an already {@code PROMOTED} revision fails with {@link DeclarationAlreadyPromoted}
 * (HTTP 409). HTTP is {@link DeclarationPromoteEndpoint}.
 * 加载修订，经 git 写 YAML，插入晋升审计并翻 PROMOTED。不 push。
 * 实体：同修订迁移须全部 APPLIED 或 CANCELLED（无行则允许）。表单不绑迁移。
 * 流程：晋升前校验固定页面路径（租户隔离再校验四积木）；不另写 page YAML，运行时读生效 flow。
 */
public final class DeclarationPromoteService {

    private final JdbcDeclarationStore store;
    private final JdbcDeclarationMigrationStore migrationStore;
    private final InternalDeclarationGit git;
    private final Path gitDir;
    private final TransactionTemplate transactions;
    private final EntityRenderer entityRenderer = new EntityRenderer();
    private final FormRenderer formRenderer = new FormRenderer();
    private final PageRenderer pageRenderer = new PageRenderer();

    public DeclarationPromoteService(
            JdbcDeclarationStore store,
            JdbcDeclarationMigrationStore migrationStore,
            InternalDeclarationGit git,
            Path gitDir,
            TransactionTemplate transactions) {
        this.store = Objects.requireNonNull(store, "store");
        this.migrationStore = Objects.requireNonNull(migrationStore, "migrationStore");
        this.git = Objects.requireNonNull(git, "git");
        this.gitDir = Objects.requireNonNull(gitDir, "gitDir");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** Promote the latest revision for the key — 晋升该键的最新修订。 */
    public DeclarationPromoteResult promoteLatest(
            String tenantId, DeclarationKind kind, String declarationKey, String subjectId) {
        DeclarationRevision revision = store.latest(tenantId, kind, declarationKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no declaration revision for " + kind.wireName() + "/" + declarationKey));
        return promoteRevision(revision, subjectId);
    }

    /** Promote an explicit revision number — 晋升指定修订号。 */
    public DeclarationPromoteResult promoteRevision(
            String tenantId,
            DeclarationKind kind,
            String declarationKey,
            int revisionNumber,
            String subjectId) {
        DeclarationRevision revision = store.findRevision(tenantId, kind, declarationKey, revisionNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "declaration revision not found: " + kind.wireName() + "/" + declarationKey
                                + "@r" + revisionNumber));
        return promoteRevision(revision, subjectId);
    }

    private DeclarationPromoteResult promoteRevision(DeclarationRevision revision, String subjectId) {
        String sid = requireSubjectId(subjectId);
        if (JdbcDeclarationStore.PROMOTED_STATE.equals(revision.draftState())) {
            throw new DeclarationAlreadyPromoted(
                    "declaration already promoted: "
                            + revision.kind().wireName()
                            + "/"
                            + revision.declarationKey()
                            + "@r"
                            + revision.revision());
        }
        requireDraftConstraints(revision);
        requireEntityMigrationsSettled(revision);
        // RT-5: flow promote binds runtime pages from YAML (no separate page declaration write).
        // 流程晋升：从 YAML 绑定运行时页面（不另写 page 声明）。
        if (revision.kind() == DeclarationKind.FLOW) {
            DeclarationRuntimePages.ensureAfterFlowPromote(revision.yamlBody());
        }
        // Git write outside the DB transaction (filesystem); DB rows in one transaction after success.
        // git 写在库事务外；成功后再在同一事务里写审计行并翻状态。
        String sha = git.promote(
                gitDir,
                revision.tenantId(),
                revision.kind(),
                revision.declarationKey(),
                revision.revision(),
                revision.yamlBody(),
                sid);
        DeclarationPromoteResult result = transactions.execute(status -> {
            store.recordPromote(
                    revision.tenantId(),
                    revision.kind(),
                    revision.declarationKey(),
                    revision.revision(),
                    sha,
                    sid);
            store.markPromoted(
                    revision.tenantId(), revision.kind(), revision.declarationKey(), revision.revision());
            return new DeclarationPromoteResult(
                    revision.tenantId(),
                    revision.kind(),
                    revision.declarationKey(),
                    revision.revision(),
                    sha);
        });
        return Objects.requireNonNull(result, "promote transaction returned null");
    }


    /**
     * Re-validate permission catalog + form effect whitelist on promote (RT-3) —
     * 晋升时再次校验权限目录与表单副作用白名单（RT-3）。
     */
    private void requireDraftConstraints(DeclarationRevision revision) {
        String yaml = revision.yamlBody();
        try {
            switch (revision.kind()) {
                case ENTITY -> {
                    RenderedEntity entity = entityRenderer.render(yaml);
                    DeclarationDraftConstraints.requireEntity(entity);
                }
                case FORM -> {
                    RenderedForm form = formRenderer.render(yaml);
                    DeclarationDraftConstraints.requireForm(form);
                }
                case FLOW -> {
                    RenderedFlow flow = pageRenderer.render(yaml);
                    DeclarationDraftConstraints.requireFlow(flow);
                }
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null || ex.getMessage().isBlank() ? "invalid yaml" : ex.getMessage();
            throw new IllegalArgumentException(message, ex);
        }
    }

    /**
     * Entity promote: block when any migration for this revision is still open or FAILED —
     * 实体晋升：同修订若有未 APPLIED/CANCELLED 的迁移则拒绝。
     */
    private void requireEntityMigrationsSettled(DeclarationRevision revision) {
        if (revision.kind() != DeclarationKind.ENTITY) {
            return;
        }
        List<DeclarationMigration> rows = migrationStore.listForRevision(
                revision.tenantId(),
                revision.kind(),
                revision.declarationKey(),
                revision.revision());
        List<DeclarationMigration> blockers = rows.stream()
                .filter(m -> !JdbcDeclarationMigrationStore.APPLIED.equals(m.status())
                        && !JdbcDeclarationMigrationStore.CANCELLED.equals(m.status()))
                .toList();
        if (blockers.isEmpty()) {
            return;
        }
        String statuses = blockers.stream()
                .map(m -> m.migrationId() + "=" + m.status())
                .collect(Collectors.joining(", "));
        throw new DeclarationPromoteBlockedByMigration(
                "entity promote blocked until migrations for revision "
                        + revision.revision()
                        + " are APPLIED or CANCELLED ("
                        + statuses
                        + "); enqueue → review → apply before promote when schema changes");
    }

    private static String requireSubjectId(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectId required");
        }
        return subjectId.trim();
    }

    /** Outcome of one promote — 一次晋升的结果。 */
    public record DeclarationPromoteResult(
            String tenantId,
            DeclarationKind kind,
            String declarationKey,
            int revision,
            String gitCommitSha) {}
}
