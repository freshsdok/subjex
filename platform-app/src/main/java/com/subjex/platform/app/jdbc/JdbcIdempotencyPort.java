package com.subjex.platform.app.jdbc;

import com.subjex.platform.contract.idempotency.IdempotencyClaim;
import com.subjex.platform.contract.idempotency.IdempotencyPort;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcIdempotencyPort — JDBC 幂等端口：{@link IdempotencyPort} 的唯一实现。
 * <p>
 * The claim is a row in {@code idempotency_claim}. A repeated token is the same row, not a new task.
 * 占用是 {@code idempotency_claim} 里的一行。重复记号仍是这一行，不会变成一条新任务。
 */
public final class JdbcIdempotencyPort implements IdempotencyPort {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcIdempotencyPort(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<IdempotencyClaim> find(String tenantId, String idempotencyToken) {
        return jdbc.query(
                """
                SELECT tenant_id, idempotency_token, request_fingerprint, task_id
                FROM idempotency_claim
                WHERE tenant_id = ? AND idempotency_token = ?
                """,
                (row, rowNumber) -> new IdempotencyClaim(
                        row.getString("tenant_id"),
                        row.getString("idempotency_token"),
                        row.getString("request_fingerprint"),
                        row.getString("task_id")),
                tenantId,
                idempotencyToken).stream().findFirst();
    }

    @Override
    public void insert(IdempotencyClaim claim) {
        jdbc.update(
                """
                INSERT INTO idempotency_claim
                    (tenant_id, idempotency_token, request_fingerprint, task_id, claimed_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                claim.tenantId(),
                claim.idempotencyToken(),
                claim.requestFingerprint(),
                claim.taskId(),
                Timestamp.from(clock.instant()));
    }
}
