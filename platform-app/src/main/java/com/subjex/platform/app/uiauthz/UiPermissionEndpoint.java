package com.subjex.platform.app.uiauthz;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * UiPermissionEndpoint — 运行时 UI 权限 Manifest 投影（ADR 0010 / PC-2 / PC-6b）。
 * <p>
 * {@code GET /api/v1/ui-permissions?client=console} — any signed-in operator; each entry carries
 * {@code allowed} from the principal's permission names (FE UX; not a security boundary).
 * Effective body: latest {@code PROMOTED} ui-authz draft, else classpath baseline (drafts excluded).
 * 已登录即可；每条带 {@code allowed}（前端体验；非安全边界）。
 * 生效体：最新已晋升 ui-authz，否则 classpath（不含未晋升草稿）。
 */
@RestController
public class UiPermissionEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/ui-permissions";

    private final UiPermissionDraftService drafts;

    public UiPermissionEndpoint(UiPermissionDraftService drafts) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
    }

    /**
     * Effective Manifest for {@code client} with per-entry {@code allowed} — 带 allowed 的有效 Manifest。
     *
     * @param client Manifest client id (default {@code console}) — 客户端 id（默认 console）
     */
    @GetMapping(PATH)
    public UiPermissionManifestDocument get(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(name = "client", defaultValue = UiPermissionManifest.CLIENT_CONSOLE) String client) {
        Objects.requireNonNull(operator, "operator");
        if (client == null || client.isBlank()) {
            throw new UiPermissionRejected("client is required");
        }
        UiPermissionManifest manifest = drafts.effective(client.trim());
        List<UiPermissionEntryDocument> entries = manifest.entries().stream()
                .map(entry -> toDocument(entry, operator.permissionNames()))
                .toList();
        return new UiPermissionManifestDocument(
                manifest.version(),
                manifest.client(),
                new UiPermissionDefaultsDocument(
                        manifest.defaults().lockedMenuEffect(),
                        manifest.defaults().missingButtonEffect()),
                entries);
    }

    private static UiPermissionEntryDocument toDocument(
            UiPermissionEntry entry, java.util.Set<String> permissionNames) {
        return new UiPermissionEntryDocument(
                entry.id(),
                entry.kind().wireName(),
                entry.href(),
                entry.route(),
                entry.permission(),
                entry.anyOf(),
                UiPermissionAccess.allowed(entry, permissionNames));
    }

    /**
     * Wire document for one Manifest — 一份 Manifest 的线文档。
     */
    public record UiPermissionManifestDocument(
            int version,
            String client,
            UiPermissionDefaultsDocument defaults,
            List<UiPermissionEntryDocument> entries) {}

    /** Defaults block — 默认 UX 效果块。 */
    public record UiPermissionDefaultsDocument(String lockedMenuEffect, String missingButtonEffect) {}

    /**
     * One binding + whether the caller may unlock it — 一条绑定 + 调用方是否可解锁。
     */
    public record UiPermissionEntryDocument(
            String id,
            String kind,
            String href,
            String route,
            String permission,
            List<String> anyOf,
            boolean allowed) {}
}
