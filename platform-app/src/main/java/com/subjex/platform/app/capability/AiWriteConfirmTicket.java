package com.subjex.platform.app.capability;

import java.time.Instant;
import java.util.Objects;

/**
 * AiWriteConfirmTicket — 写回确认票：预览之后、写业务状态之前的一次性凭证。
 * <p>
 * Bound to capability + digests. Preview-only flows never need a ticket.
 * 绑定能力与摘要。纯预览流程不需要票。
 */
public record AiWriteConfirmTicket(
        String ticketId,
        String capabilityId,
        String inputDigest,
        String previewDigest,
        Instant expiresAt) {

    public AiWriteConfirmTicket {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(inputDigest, "inputDigest");
        Objects.requireNonNull(previewDigest, "previewDigest");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
