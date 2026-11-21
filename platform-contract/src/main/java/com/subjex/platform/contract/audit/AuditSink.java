package com.subjex.platform.contract.audit;

/**
 * AuditSink — optional SIEM/file delivery of already-recorded audit facts (WP9-1).
 * 可选的 SIEM/文件投递：只投递已经写入的审计事实。
 * <p>
 * Not a second audit bus and not a second outbox. Implementations must not store secrets or tokens.
 * 不是第二条审计通道，也不是第二张出箱。实现不得存放密钥或令牌。
 */
public interface AuditSink {

    /**
     * Accept one already-persisted entry. No-op when the sink is disabled.
     * 接收一条已落库的条目；未启用时为空操作。
     */
    void accept(AuditEntry entry);

    /** Whether this sink will write — 是否会实际写出. */
    boolean enabled();
}
