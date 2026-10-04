package com.subjex.platform.contract.audit;

/**
 * AuditPort — 审计端口：记下一条审计事实的唯一入口。
 * <p>
 * One port. Do not add a second audit channel.
 * 只有这一个端口，不要再开第二条审计通道。
 */
public interface AuditPort {

    void record(AuditEntry entry);
}
