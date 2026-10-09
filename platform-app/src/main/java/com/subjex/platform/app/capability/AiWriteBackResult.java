package com.subjex.platform.app.capability;

import java.util.Objects;

/**
 * AiWriteBackResult — 写回结果：本片默认 noop，不落库；suggestion 为预览文本草稿建议。
 */
public record AiWriteBackResult(String sink, String suggestion, boolean persisted) {

    public AiWriteBackResult {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(suggestion, "suggestion");
    }
}
