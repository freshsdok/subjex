package com.subjex.platform.app.admin;

import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.contract.task.DeadLetter;
import com.subjex.platform.contract.task.TaskRecord;
import com.subjex.platform.contract.tenant.TenantRecord;
import java.util.List;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminReadEndpoint — 只读管理台：列出租户、任务、死信、审计条目，并给出存活与就绪。
 * <p>
 * Liveness and readiness are the same states Actuator probes expose. There is no write mapping here, and no database console.
 * 存活与就绪就是 Actuator 探针暴露的那两个状态。这里没有写操作映射，也没有数据库控制台。
 */
@RestController
@RequestMapping("/admin")
public class AdminReadEndpoint {

    private final JdbcAdminReader adminReader;
    private final ApplicationAvailability availability;

    public AdminReadEndpoint(JdbcAdminReader adminReader, ApplicationAvailability availability) {
        this.adminReader = adminReader;
        this.availability = availability;
    }

    @GetMapping("/tenants")
    public List<TenantRecord> tenants() {
        return adminReader.tenants();
    }

    @GetMapping("/tasks")
    public List<TaskRecord> tasks() {
        return adminReader.tasks();
    }

    @GetMapping("/dead-letters")
    public List<DeadLetter> deadLetters() {
        return adminReader.deadLetters();
    }

    /**
     * Newest 200 audit entries — 最新的 200 条审计条目。
     */
    @GetMapping("/audit")
    public List<AuditRow> audit() {
        return adminReader.auditEntries(AuditPage.ROW_LIMIT);
    }

    @GetMapping("/health")
    public AdminHealth health() {
        return new AdminHealth(
                availability.getLivenessState().name(),
                availability.getReadinessState().name());
    }
}
