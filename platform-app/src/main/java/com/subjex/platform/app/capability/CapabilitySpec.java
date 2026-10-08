package com.subjex.platform.app.capability;

import java.util.Objects;

/**
 * CapabilitySpec — 能力目录一项：id、种类、中英标题与摘要。
 */
public record CapabilitySpec(
        String id, CapabilityKind kind, String titleEn, String titleZh, String summaryEn, String summaryZh) {

    public CapabilitySpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(titleEn, "titleEn");
        Objects.requireNonNull(titleZh, "titleZh");
        Objects.requireNonNull(summaryEn, "summaryEn");
        Objects.requireNonNull(summaryZh, "summaryZh");
    }
}
