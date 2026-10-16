package com.subjex.platform.app.organization;

/**
 * RelationKind — OrganizationRelation kinds.
 * CONTAINS drives tree + OrgScope; MATRIX_OF is Later L1 dotted-line (not in OrgScope).
 * CONTAINS 驱动树与 OrgScope；MATRIX_OF 为 Later L1 虚线汇报（不入 OrgScope）。
 */
public final class RelationKind {

    /** Parent contains child — 父包含子。 */
    public static final String CONTAINS = "CONTAINS";

    /**
     * Matrix / dotted-line: from = matrix supervisor org, to = supervised org.
     * Mirrors CONTAINS direction (parent → child). Does not participate in OrgScope closure.
     * 矩阵/虚线：from=矩阵主管组织，to=被管组织；不参与 OrgScope 闭包。
     */
    public static final String MATRIX_OF = "MATRIX_OF";

    private RelationKind() {}

    public static boolean isKnown(String kind) {
        return CONTAINS.equals(kind) || MATRIX_OF.equals(kind);
    }
}
