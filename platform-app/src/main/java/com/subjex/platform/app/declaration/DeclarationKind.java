package com.subjex.platform.app.declaration;

/**
 * DeclarationKind — 声明种类：实体 / 表单 / 流程。
 * <p>
 * Wire values match {@code declaration_revision.declaration_kind}: {@code entity}, {@code form}, {@code flow}.
 * 落库值与 {@code declaration_revision.declaration_kind} 一致。
 */
public enum DeclarationKind {

    ENTITY("entity"),
    FORM("form"),
    FLOW("flow");

    private final String wireName;

    DeclarationKind(String wireName) {
        this.wireName = wireName;
    }

    /** Value stored in {@code declaration_kind} — 写入 {@code declaration_kind} 的值。 */
    public String wireName() {
        return wireName;
    }

    /** Parse a wire name; blank or unknown throws — 解析落库名；空或未知抛错。 */
    public static DeclarationKind fromWire(String wireName) {
        if (wireName == null || wireName.isBlank()) {
            throw new IllegalArgumentException("declarationKind required");
        }
        String trimmed = wireName.trim();
        for (DeclarationKind kind : values()) {
            if (kind.wireName.equals(trimmed)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown declarationKind: " + trimmed);
    }
}
