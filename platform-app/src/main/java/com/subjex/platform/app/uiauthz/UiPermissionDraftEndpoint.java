package com.subjex.platform.app.uiauthz;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.declaration.DeclarationPromoteService;
import com.subjex.platform.app.declaration.DeclarationRevision;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * UiPermissionDraftEndpoint — UI 权限 Manifest 草稿 / 晋升 / 导出（PC-6a / PC-6b）。
 * <p>
 * {@code GET/PUT /api/v1/ui-permissions/draft?client=console} — declaration.read / write.
 * {@code POST .../draft/promote} — declaration.promote (single operator; platform UI bindings).
 * {@code GET .../draft/export} — effective YAML (PROMOTED else classpath) for write-back / review.
 * 草稿需 declaration.read/write；晋升需 declaration.promote；导出为生效 YAML。
 */
@RestController
public class UiPermissionDraftEndpoint {

    public static final String PATH = JsonApi.BASE + "/ui-permissions/draft";

    private final UiPermissionDraftService drafts;

    public UiPermissionDraftEndpoint(UiPermissionDraftService drafts) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
    }

    /** Load draft or 404 — 读取草稿，无则 404。 */
    @GetMapping(PATH)
    public DraftDocument get(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(name = "client", defaultValue = UiPermissionManifest.CLIENT_CONSOLE) String client) {
        Objects.requireNonNull(operator, "operator");
        DeclarationRevision row = drafts
                .latestDraft(client)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        UiPermissionManifest manifest = UiPermissionManifestCatalog.parseYaml(row.yamlBody());
        return toDraftDocument(row, manifest);
    }

    /** Classpath baseline as editable document (seed editor) — classpath 基线供编辑器种子。 */
    @GetMapping(PATH + "/baseline")
    public ManifestBodyDocument baseline(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(name = "client", defaultValue = UiPermissionManifest.CLIENT_CONSOLE) String client) {
        Objects.requireNonNull(operator, "operator");
        return toBody(drafts.classpathBaseline(client));
    }

    /**
     * Export effective Manifest YAML (PROMOTED else classpath) —
     * 导出生效 Manifest YAML（已晋升否则 classpath）。
     * <p>
     * Write-back path: download and commit into {@code ui-authz/{client}-ui.yaml} when desired;
     * runtime does not rewrite the classpath jar.
     * 回写路径：下载后按需提交进 {@code ui-authz/{client}-ui.yaml}；运行时不改 jar 内 classpath。
     */
    @GetMapping(PATH + "/export")
    public ResponseEntity<String> export(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(name = "client", defaultValue = UiPermissionManifest.CLIENT_CONSOLE) String client) {
        Objects.requireNonNull(operator, "operator");
        String yaml = drafts.exportEffectiveYaml(client);
        String filename = client.trim() + "-ui.yaml";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/yaml"))
                .body(yaml);
    }

    /**
     * Save validated draft from JSON body — 校验后保存 JSON 草稿。
     */
    @PutMapping(PATH)
    public DraftDocument put(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(name = "client", defaultValue = UiPermissionManifest.CLIENT_CONSOLE) String client,
            @RequestBody ManifestBodyDocument body) {
        Objects.requireNonNull(operator, "operator");
        if (body == null) {
            throw new UiPermissionRejected("body required");
        }
        UiPermissionManifest manifest = fromBody(client, body);
        DeclarationRevision saved = drafts.saveDraft(client, manifest, operator.subjectId());
        return toDraftDocument(saved, manifest);
    }

    /**
     * Promote latest DRAFT to live overlay — 将最新草稿晋升为线上叠层。
     */
    @PostMapping(PATH + "/promote")
    public PromoteDocument promote(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(name = "client", defaultValue = UiPermissionManifest.CLIENT_CONSOLE) String client) {
        Objects.requireNonNull(operator, "operator");
        DeclarationPromoteService.DeclarationPromoteResult result =
                drafts.promoteLatestDraft(client, operator.subjectId());
        return new PromoteDocument(
                result.tenantId(),
                result.kind().wireName(),
                result.declarationKey(),
                result.revision(),
                result.gitCommitSha());
    }

    private static UiPermissionManifest fromBody(String client, ManifestBodyDocument body) {
        String resolvedClient = body.client() == null || body.client().isBlank() ? client.trim() : body.client().trim();
        if (!client.trim().equals(resolvedClient)) {
            throw new UiPermissionRejected("body.client must match query client");
        }
        UiPermissionManifestDefaults defaults = body.defaults() == null
                ? UiPermissionManifestDefaults.lockedDefaults()
                : new UiPermissionManifestDefaults(
                        body.defaults().lockedMenuEffect(), body.defaults().missingButtonEffect());
        List<UiPermissionEntry> entries = new ArrayList<>();
        if (body.entries() == null || body.entries().isEmpty()) {
            throw new UiPermissionRejected("entries must be a non-empty list");
        }
        for (EntryBody e : body.entries()) {
            if (e == null || e.id() == null || e.kind() == null) {
                throw new UiPermissionRejected("each entry needs id and kind");
            }
            entries.add(new UiPermissionEntry(
                    e.id(),
                    UiPermissionEntryKind.fromWire(e.kind()),
                    e.href(),
                    e.route(),
                    e.permission(),
                    e.anyOf() == null ? List.of() : e.anyOf()));
        }
        UiPermissionManifest built =
                new UiPermissionManifest(body.version() < 1 ? 1 : body.version(), resolvedClient, defaults, entries);
        // Fail-closed via parse round-trip — 经解析往返失败关闭。
        return UiPermissionManifestCatalog.parseYaml(UiPermissionManifestWriter.toYaml(built));
    }

    private static DraftDocument toDraftDocument(DeclarationRevision row, UiPermissionManifest manifest) {
        return new DraftDocument(
                row.revision(),
                row.draftState(),
                row.updatedAt(),
                row.updatedBySubjectId(),
                toBody(manifest));
    }

    private static ManifestBodyDocument toBody(UiPermissionManifest manifest) {
        List<EntryBody> entries = manifest.entries().stream()
                .map(e -> new EntryBody(
                        e.id(),
                        e.kind().wireName(),
                        e.href(),
                        e.route(),
                        e.permission(),
                        e.anyOf()))
                .toList();
        return new ManifestBodyDocument(
                manifest.version(),
                manifest.client(),
                new DefaultsBody(
                        manifest.defaults().lockedMenuEffect(),
                        manifest.defaults().missingButtonEffect()),
                entries);
    }

    public record DraftDocument(
            int revision,
            String draftState,
            Instant updatedAt,
            String updatedBySubjectId,
            ManifestBodyDocument manifest) {}

    public record ManifestBodyDocument(
            int version, String client, DefaultsBody defaults, List<EntryBody> entries) {}

    public record DefaultsBody(String lockedMenuEffect, String missingButtonEffect) {}

    public record EntryBody(
            String id,
            String kind,
            String href,
            String route,
            String permission,
            List<String> anyOf) {}

    /** Promote outcome — 晋升结果。 */
    public record PromoteDocument(
            String tenantId, String kind, String declarationKey, int revision, String gitCommitSha) {}
}
