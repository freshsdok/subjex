package com.subjex.platform.app.capability;

import java.util.Objects;

/**
 * NoOpAiWriteBackSink — 空写回：不改实体/库；返回草稿建议文本供控制台展示。
 */
public final class NoOpAiWriteBackSink implements AiWriteBackSink {

    @Override
    public AiWriteBackResult apply(String capabilityId, String previewText, AiWriteConfirmTicket ticket) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(previewText, "previewText");
        Objects.requireNonNull(ticket, "ticket");
        return new AiWriteBackResult("noop", previewText, false);
    }
}
