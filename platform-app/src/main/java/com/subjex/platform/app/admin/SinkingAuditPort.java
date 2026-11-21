package com.subjex.platform.app.admin;

import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.audit.AuditSink;
import java.util.Objects;

/**
 * SinkingAuditPort — persist via the existing JDBC {@link AuditPort}, then optionally fan out to {@link AuditSink}.
 * 先经现有 JDBC 审计端口落库，再可选地投递到 {@link AuditSink}。
 * <p>
 * This is a write-path fan-out, not a second outbox table or a second audit bus.
 * 这是写路径扇出，不是第二张出箱表，也不是第二条审计总线。
 */
public final class SinkingAuditPort implements AuditPort {

    private final AuditPort inner;
    private final AuditSink sink;

    public SinkingAuditPort(AuditPort inner, AuditSink sink) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.sink = sink == null ? disabledSink() : sink;
    }

    @Override
    public void record(AuditEntry entry) {
        inner.record(entry);
        try {
            sink.accept(entry);
        } catch (RuntimeException ignored) {
            // Sink must not roll back a recorded fact — 投递失败不得回滚已记录的事实.
        }
    }

    private static AuditSink disabledSink() {
        return new AuditSink() {
            @Override
            public void accept(AuditEntry entry) {}

            @Override
            public boolean enabled() {
                return false;
            }
        };
    }
}
