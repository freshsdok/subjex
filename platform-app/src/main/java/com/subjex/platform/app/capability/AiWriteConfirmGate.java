package com.subjex.platform.app.capability;

/**
 * AiWriteConfirmGate — AI 写回确认门闩：无有效票不得写业务状态。
 * <p>
 * Issue after operator reviews preview; {@link #consume} is single-use fail-closed.
 * 操作员审阅预览后签发；{@link #consume} 一次性、失败关闭。
 */
public interface AiWriteConfirmGate {

    /**
     * Issue a one-shot ticket for a previewed completion — 为已预览的补全签发一次性票。
     */
    AiWriteConfirmTicket issue(ModelCompletionRequest request, ModelCompletionResult preview);

    /**
     * Consume a ticket (single use). Throws if missing, expired, mismatched, or already used —
     * 消费票（一次性）。缺失/过期/不匹配/已用则抛错。
     */
    void consume(AiWriteConfirmTicket ticket);
}
