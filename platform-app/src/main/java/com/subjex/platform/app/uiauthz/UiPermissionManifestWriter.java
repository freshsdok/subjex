package com.subjex.platform.app.uiauthz;

import java.util.Objects;

/**
 * UiPermissionManifestWriter — Manifest → YAML 文本（草稿落库用，ADR 0010 / PC-6a）。
 * <p>
 * Emits a stable subset matching {@link UiPermissionManifestCatalog} parse rules.
 * 写出与 Catalog 解析规则一致的稳定子集。
 */
public final class UiPermissionManifestWriter {

    private UiPermissionManifestWriter() {}

    /** Serialize manifest to YAML body — 序列化为 YAML 正文。 */
    public static String toYaml(UiPermissionManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        StringBuilder sb = new StringBuilder();
        sb.append("# UI permission Manifest draft (PC-6a)\n");
        sb.append("version: ").append(manifest.version()).append('\n');
        sb.append("client: ").append(manifest.client()).append('\n');
        sb.append("defaults:\n");
        sb.append("  lockedMenuEffect: ").append(manifest.defaults().lockedMenuEffect()).append('\n');
        sb.append("  missingButtonEffect: ").append(manifest.defaults().missingButtonEffect()).append('\n');
        sb.append("entries:\n");
        for (UiPermissionEntry entry : manifest.entries()) {
            sb.append("  - id: ").append(entry.id()).append('\n');
            sb.append("    kind: ").append(entry.kind().wireName()).append('\n');
            if (entry.href() != null) {
                sb.append("    href: ").append(entry.href()).append('\n');
            }
            if (entry.route() != null) {
                sb.append("    route: ").append(entry.route()).append('\n');
            }
            if (entry.permission() != null) {
                sb.append("    permission: ").append(entry.permission()).append('\n');
            }
            if (!entry.anyOf().isEmpty()) {
                sb.append("    anyOf:\n");
                for (String name : entry.anyOf()) {
                    sb.append("      - ").append(name).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
