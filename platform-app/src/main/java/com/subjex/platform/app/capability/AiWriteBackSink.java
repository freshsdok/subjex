package com.subjex.platform.app.capability;

/**
 * AiWriteBackSink — AI 写回落点：须在确认票 {@link AiWriteConfirmGate#consume} 之后调用。
 */
public interface AiWriteBackSink {

    AiWriteBackResult apply(String capabilityId, String previewText, AiWriteConfirmTicket ticket);
}
