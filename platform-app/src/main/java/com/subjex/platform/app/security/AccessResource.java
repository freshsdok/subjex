package com.subjex.platform.app.security;

/**
 * AccessResource — 访问判定中的资源：种类 + 可选标识（如 formKey / entityKey）。
 * <p>
 * Keep small and immutable. Blank id is allowed when the check is catalog-level.
 * 保持小而不可变；目录级检查时 id 可空白。
 */
public record AccessResource(String kind, String id) {

    public AccessResource {
        kind = kind == null ? "" : kind.trim();
        id = id == null ? "" : id.trim();
    }

    public static AccessResource of(String kind, String id) {
        return new AccessResource(kind, id);
    }
}
