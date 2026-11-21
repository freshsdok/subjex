package com.subjex.platform.app.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.jdbc.JdbcIdempotencyPort;
import com.subjex.platform.app.jdbc.JdbcTaskMessagePort;
import com.subjex.platform.app.lock.SingleProcessLock;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcTenantAdmin;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.delivery.DeliveryAttempt;
import com.subjex.platform.contract.delivery.DeliveryPort;
import com.subjex.platform.contract.task.DeliveryResult;
import com.subjex.platform.contract.task.TaskCommand;
import com.subjex.platform.contract.task.TaskKind;
import com.subjex.platform.contract.task.TaskRecord;
import com.subjex.platform.contract.tenant.TenantState;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * TenantAutoCreateOnSubmitTest — purpose/gate WP1-3: submit must not insert a missing tenant when
 * auto-create is off; the local escape hatch still may; explicit admin create and SUSPENDED refusal stay.
 * <p>
 * 目的/门禁 WP1-3：关闭自动建户时提交不得插入缺失租户；本机逃生仍可建户；显式管理与停用拒绝保持不变。
 */
class TenantAutoCreateOnSubmitTest {

    private static final String MISSING = "tenant-wp13-missing";
    private static final String ACTIVE = "tenant-wp13-active";
    private static final String SUSPENDED = "tenant-wp13-off";

    private JdbcTemplate jdbc;
    private DataSource source;
    private JdbcTenantAdmin tenantAdmin;

    @BeforeEach
    void migrate() {
        source = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(source);
        tenantAdmin = new JdbcTenantAdmin(jdbc);
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, 'Active', 'ACTIVE')",
                ACTIVE);
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, 'Off', 'SUSPENDED')",
                SUSPENDED);
    }

    @Test
    void flagOffDoesNotAutoCreateAndFailsClosedWhenTenantMissing() {
        JdbcTaskMessagePort port = port(false);
        TenantNotFoundException ex =
                assertThrows(TenantNotFoundException.class, () -> port.submit(command(MISSING, "tok-off")));
        assertEquals(TenantNotFoundException.REASON, ex.reason());
        assertEquals(0, countTenants(MISSING));
        assertEquals(0, countTasks(MISSING));
    }

    @Test
    void flagOnAutoCreatesMissingTenantOnSubmit() {
        JdbcTaskMessagePort port = port(true);
        TaskRecord task = port.submit(command(MISSING, "tok-on"));
        assertEquals(MISSING, task.tenantId());
        assertEquals(1, countTenants(MISSING));
        assertEquals(
                TenantState.ACTIVE.name(),
                jdbc.queryForObject(
                        "SELECT tenant_state FROM tenant WHERE tenant_id = ?", String.class, MISSING));
        assertEquals(1, countTasks(MISSING));
    }

    @Test
    void existingActiveTenantCanBeSubmittedWhenFlagOff() {
        JdbcTaskMessagePort port = port(false);
        TaskRecord task = port.submit(command(ACTIVE, "tok-existing"));
        assertEquals(ACTIVE, task.tenantId());
        assertEquals(1, countTenants(ACTIVE));
        assertEquals(1, countTasks(ACTIVE));
    }

    @Test
    void suspendedTenantStillRefusesSubmitWhetherFlagOnOrOff() {
        assertThrows(TenantDisabledException.class, () -> port(false).submit(command(SUSPENDED, "tok-off-sus")));
        assertThrows(TenantDisabledException.class, () -> port(true).submit(command(SUSPENDED, "tok-on-sus")));
        assertEquals(
                TenantState.SUSPENDED.name(),
                jdbc.queryForObject(
                        "SELECT tenant_state FROM tenant WHERE tenant_id = ?", String.class, SUSPENDED));
        assertEquals(0, countTasks(SUSPENDED));
    }

    @Test
    void explicitAdminCreateStillWorksAndReservedPlatformStaysImmutable() {
        tenantAdmin.create("tenant-wp13-admin", "Admin created");
        assertEquals(1, countTenants("tenant-wp13-admin"));
        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> tenantAdmin.create("platform", "nope"))
                        .getMessage()
                        .contains("reserved"));
        assertEquals("platform", tenantAdmin.require("platform").tenantId());
    }

    private JdbcTaskMessagePort port(boolean autoCreate) {
        Clock clock = Clock.systemUTC();
        DeliveryPort delivery = (event, failureRequested) -> new DeliveryAttempt(DeliveryResult.published(), "test");
        return new JdbcTaskMessagePort(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(source)),
                new JdbcIdempotencyPort(jdbc, clock),
                new JdbcAuditPort(jdbc),
                new SingleProcessLock(clock),
                delivery,
                OpenTelemetry.noop(),
                new ObjectMapper(),
                clock,
                autoCreate);
    }

    private static TaskCommand command(String tenantId, String token) {
        return new TaskCommand(
                tenantId, token, "identity-wp13", TaskKind.DETERMINISTIC, "admit-subject", true, null, null, null);
    }

    private int countTenants(String tenantId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    private int countTasks(String tenantId) {
        Integer count =
                jdbc.queryForObject("SELECT COUNT(*) FROM platform_task WHERE tenant_id = ?", Integer.class, tenantId);
        return count == null ? 0 : count;
    }
}
