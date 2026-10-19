package com.subjex.platform.app.uiauthz;

import com.subjex.platform.app.declaration.DeclarationKind;
import com.subjex.platform.app.declaration.DeclarationPromoteService;
import com.subjex.platform.app.declaration.DeclarationRevision;
import com.subjex.platform.app.declaration.InternalDeclarationGit;
import com.subjex.platform.app.declaration.JdbcDeclarationStore;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * UiPermissionDraftService — UI 权限 Manifest 草稿与晋升（声明双轨语义，PC-6a/6b）。
 * <p>
 * Store: tenant {@code platform}, kind {@code ui-authz}, key = client id.
 * Live resolution: {@code PROMOTED} YAML if any, else classpath baseline (drafts do not affect live).
 * Promote writes internal git {@code platform/ui-authz/{client}.yaml} + marks PROMOTED.
 * 落库租户 platform。生效：已晋升 YAML，否则 classpath（草稿不影响线上）。晋升写内建 git。
 */
public final class UiPermissionDraftService {

    /** Reserved tenant for platform-wide UI bindings — 平台级 UI 绑定的保留租户。 */
    public static final String PLATFORM_TENANT = "platform";

    private final JdbcDeclarationStore store;
    private final UiPermissionManifestCatalog classpathCatalog;
    private final InternalDeclarationGit git;
    private final Path gitDir;
    private final TransactionTemplate transactions;

    public UiPermissionDraftService(
            JdbcDeclarationStore store,
            UiPermissionManifestCatalog classpathCatalog,
            InternalDeclarationGit git,
            Path gitDir,
            TransactionTemplate transactions) {
        this.store = Objects.requireNonNull(store, "store");
        this.classpathCatalog = Objects.requireNonNull(classpathCatalog, "classpathCatalog");
        this.git = Objects.requireNonNull(git, "git");
        this.gitDir = Objects.requireNonNull(gitDir, "gitDir");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** Classpath baseline for client — 该 client 的 classpath 基线。 */
    public UiPermissionManifest classpathBaseline(String client) {
        return classpathCatalog.require(client);
    }

    /**
     * Live effective Manifest: latest {@code PROMOTED} else classpath —
     * 线上生效：最新已晋升，否则 classpath（不含未晋升草稿）。
     */
    public UiPermissionManifest effective(String client) {
        return latestPromotedManifest(client).orElseGet(() -> classpathBaseline(client));
    }

    /** Latest DRAFT if any — 若有则返回最新草稿。 */
    public Optional<DeclarationRevision> latestDraft(String client) {
        String key = requireClient(client);
        return store.latestDraft(PLATFORM_TENANT, DeclarationKind.UI_AUTHZ, key);
    }

    /** Latest PROMOTED if any — 若有则返回最新已晋升。 */
    public Optional<DeclarationRevision> latestPromoted(String client) {
        String key = requireClient(client);
        return store.latestPromoted(PLATFORM_TENANT, DeclarationKind.UI_AUTHZ, key);
    }

    public Optional<UiPermissionManifest> latestDraftManifest(String client) {
        return latestDraft(client).map(row -> UiPermissionManifestCatalog.parseYaml(row.yamlBody()));
    }

    public Optional<UiPermissionManifest> latestPromotedManifest(String client) {
        return latestPromoted(client).map(row -> UiPermissionManifestCatalog.parseYaml(row.yamlBody()));
    }

    /**
     * Validate + save draft YAML; returns stored revision —
     * 校验并保存草稿；返回落库修订。
     */
    public DeclarationRevision saveDraft(String client, UiPermissionManifest manifest, String subjectId) {
        String key = requireClient(client);
        Objects.requireNonNull(manifest, "manifest");
        if (!key.equals(manifest.client())) {
            throw new UiPermissionRejected("manifest client must match path client " + key);
        }
        String yaml = UiPermissionManifestWriter.toYaml(manifest);
        UiPermissionManifestCatalog.parseYaml(yaml);
        return store.saveDraft(PLATFORM_TENANT, DeclarationKind.UI_AUTHZ, key, yaml, subjectId);
    }

    /**
     * Promote latest DRAFT to live overlay (internal git + PROMOTED) —
     * 将最新草稿晋升为线上叠层（内建 git + PROMOTED）。
     * <p>
     * Single operator with {@code declaration.promote} (platform UI bindings; not role grants).
     * 单操作员持 {@code declaration.promote}（平台 UI 绑定；非角色授予）。
     */
    public DeclarationPromoteService.DeclarationPromoteResult promoteLatestDraft(String client, String subjectId) {
        String key = requireClient(client);
        String sid = requireSubject(subjectId);
        DeclarationRevision revision = store.latestDraft(PLATFORM_TENANT, DeclarationKind.UI_AUTHZ, key)
                .orElseThrow(() -> new UiPermissionRejected("no ui-authz DRAFT to promote for client " + key));
        // Fail-closed re-validate — 晋升前再次失败关闭校验。
        UiPermissionManifestCatalog.parseYaml(revision.yamlBody());
        String sha = git.promote(
                gitDir,
                PLATFORM_TENANT,
                DeclarationKind.UI_AUTHZ,
                key,
                revision.revision(),
                revision.yamlBody(),
                sid);
        DeclarationPromoteService.DeclarationPromoteResult result = transactions.execute(status -> {
            store.recordPromote(
                    PLATFORM_TENANT,
                    DeclarationKind.UI_AUTHZ,
                    key,
                    revision.revision(),
                    sha,
                    sid);
            store.markPromoted(PLATFORM_TENANT, DeclarationKind.UI_AUTHZ, key, revision.revision());
            return new DeclarationPromoteService.DeclarationPromoteResult(
                    PLATFORM_TENANT, DeclarationKind.UI_AUTHZ, key, revision.revision(), sha);
        });
        return Objects.requireNonNull(result, "promote transaction returned null");
    }

    /** Effective Manifest as YAML text (export) — 生效 Manifest 的 YAML 文本（导出）。 */
    public String exportEffectiveYaml(String client) {
        return UiPermissionManifestWriter.toYaml(effective(client));
    }

    private static String requireClient(String client) {
        if (client == null || client.isBlank()) {
            throw new UiPermissionRejected("client is required");
        }
        return client.trim();
    }

    private static String requireSubject(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new UiPermissionRejected("subjectId required");
        }
        return subjectId.trim();
    }
}
