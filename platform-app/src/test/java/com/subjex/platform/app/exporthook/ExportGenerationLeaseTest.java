package com.subjex.platform.app.exporthook;

import static org.assertj.core.api.Assertions.assertThat;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.exporthook.ExportRequest;
import com.subjex.platform.contract.exporthook.ExportRequestStatus;
import com.subjex.platform.contract.exporthook.ExportRequesterRealm;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ExportGenerationLeaseTest — claim, heartbeat, and reclaim after expiry without holding FOR UPDATE.
 * 生成租约：认领、续租，过期后可被重新认领；不在整段写入期间持有行锁。
 */
class ExportGenerationLeaseTest {

    private static final String TENANT = "tenant-export-lease";
    private static final Instant T0 = Instant.parse("2026-11-28T01:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(2);

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void claimHeartbeatAndReclaimAfterExpiry(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        ExportRequest queued = fx.insertGenerating("req-lease-1");

        ExportRequest held = fx.claim("owner-a", queued.requestId());
        assertThat(held).isNotNull();
        assertThat(held.status()).isEqualTo(ExportRequestStatus.GENERATING);
        assertThat(fx.claim("owner-b", queued.requestId())).isNull();
        assertThat(fx.store.listClaimable(fx.now.get(), 10)).isEmpty();

        assertThat(fx.store.renewGenerationLease(queued.requestId(), "owner-a", fx.now.get(), TTL)).isTrue();
        assertThat(fx.store.renewGenerationLease(queued.requestId(), "owner-b", fx.now.get(), TTL)).isFalse();

        fx.now.set(T0.plus(TTL));
        assertThat(fx.store.renewGenerationLease(queued.requestId(), "owner-a", fx.now.get(), TTL)).isFalse();
        List<ExportRequest> claimable = fx.store.listClaimable(fx.now.get(), 10);
        assertThat(claimable).extracting(ExportRequest::requestId).containsExactly(queued.requestId());

        ExportRequest reclaimed = fx.claim("owner-b", queued.requestId());
        assertThat(reclaimed).isNotNull();
        assertThat(fx.claim("owner-a", queued.requestId())).isNull();

        ExportRequest ready = new ExportRequest(
                queued.requestId(),
                queued.tenantId(),
                queued.subjectId(),
                queued.requesterRealm(),
                queued.requesterId(),
                queued.scopeDescriptor(),
                ExportRequestStatus.READY,
                queued.approverSubjectId(),
                queued.workflowInstanceId(),
                queued.createdAt(),
                fx.now.get(),
                queued.decidedAt(),
                fx.now.get().plus(Duration.ofDays(7)),
                "export-" + queued.requestId());
        assertThat(fx.store.completeGeneration(ready, "owner-a", fx.now.get())).isFalse();
        assertThat(fx.store.completeGeneration(ready, "owner-b", fx.now.get())).isTrue();
        assertThat(fx.store.find(queued.requestId()).orElseThrow().status()).isEqualTo(ExportRequestStatus.READY);
        assertThat(fx.store.listClaimable(fx.now.get(), 10)).isEmpty();
        assertThat(fx.store.completeGeneration(ready, "owner-b", fx.now.get())).isFalse();
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void lostLeaseCannotMarkFailed(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        ExportRequest queued = fx.insertGenerating("req-lease-2");
        assertThat(fx.claim("owner-a", queued.requestId())).isNotNull();
        fx.now.set(T0.plus(TTL));
        assertThat(fx.claim("owner-b", queued.requestId())).isNotNull();

        ExportRequest failed = new ExportRequest(
                queued.requestId(),
                queued.tenantId(),
                queued.subjectId(),
                queued.requesterRealm(),
                queued.requesterId(),
                queued.scopeDescriptor(),
                ExportRequestStatus.FAILED,
                queued.approverSubjectId(),
                queued.workflowInstanceId(),
                queued.createdAt(),
                fx.now.get(),
                queued.decidedAt(),
                queued.expiresAt(),
                queued.artifactObjectKey());
        assertThat(fx.store.completeGeneration(failed, "owner-a", fx.now.get())).isFalse();
        assertThat(fx.store.find(queued.requestId()).orElseThrow().status()).isEqualTo(ExportRequestStatus.GENERATING);
    }

    private static final class Fixture {
        private final JdbcExportRequestStore store;
        private final TransactionTemplate tx;
        private final AtomicReference<Instant> now = new AtomicReference<>(T0);

        private Fixture(JdbcExportRequestStore store, TransactionTemplate tx) {
            this.store = store;
            this.tx = tx;
        }

        private static Fixture open(H2PlatformTables.Mode mode) {
            DataSource ds = H2PlatformTables.migrated(mode);
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT,
                    "Lease Tenant");
            return new Fixture(
                    new JdbcExportRequestStore(jdbc),
                    new TransactionTemplate(new DataSourceTransactionManager(ds)));
        }

        private ExportRequest insertGenerating(String requestId) {
            ExportRequest request = new ExportRequest(
                    requestId,
                    TENANT,
                    "subject-lease",
                    ExportRequesterRealm.END_USER,
                    "requester-lease",
                    "ALL_OWN",
                    ExportRequestStatus.GENERATING,
                    "approver-lease",
                    "workflow-lease",
                    T0,
                    T0,
                    T0,
                    null,
                    null);
            store.insert(request);
            return request;
        }

        private ExportRequest claim(String owner, String requestId) {
            return tx.execute(status -> store.claimGeneration(requestId, owner, now.get(), TTL).orElse(null));
        }
    }
}
