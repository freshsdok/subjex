package com.subjex.platform.contract.points;

import java.util.List;

/**
 * Subject balance plus newest-first history in one tenant. Unknown subjects are not this document.
 * 同一租户下某主体的余额与由新到旧的历史。未知主体不会得到这份文档。
 */
public record PointsAccountDocument(
        String tenantId, String subjectId, long balance, List<PointsLedgerEntry> entries) {

    public PointsAccountDocument {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
