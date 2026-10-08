package com.subjex.platform.app.security;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.tenant.TenantRecord;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * TenantManagementEndpoint — 租户管理 JSON：列表/读取、创建、改显示名、禁用/启用。
 * <p>
 * List and get need {@code admin.read}. Writes need {@code tenant.manage}. Soft-disable uses
 * {@code SUSPENDED}; reserved tenant {@code platform} cannot be mutated.
 * 列表与读取要 {@code admin.read}。写操作要 {@code tenant.manage}。软禁用用 {@code SUSPENDED}；
 * 保留租户 {@code platform} 不可改。
 */
@RestController
public class TenantManagementEndpoint {

    public static final String PATH = JsonApi.BASE + "/tenants";

    private final JdbcTenantAdmin admin;
    private final OperatorActionAudit audit;

    public TenantManagementEndpoint(JdbcTenantAdmin admin, OperatorActionAudit audit) {
        this.admin = admin;
        this.audit = audit;
    }

    @GetMapping(PATH)
    public TenantsDocument list(@RequestParam(value = "q", required = false) String query) {
        List<TenantDocument> tenants = admin.list(query).stream().map(TenantManagementEndpoint::toDocument).toList();
        return new TenantsDocument(tenants);
    }

    @GetMapping(PATH + "/{tenantId}")
    public TenantDocument get(@PathVariable("tenantId") String tenantId) {
        return admin.find(tenantId)
                .map(TenantManagementEndpoint::toDocument)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping(PATH)
    @ResponseStatus(HttpStatus.CREATED)
    public TenantDocument create(
            @AuthenticationPrincipal OperatorPrincipal actor, @RequestBody CreateTenantRequest body) {
        TenantRecord created =
                admin.create(body == null ? null : body.tenantId(), body == null ? null : body.tenantName());
        audit.record(actor, "tenant.create", created.tenantId(), AuditOutcome.ALLOWED);
        return toDocument(created);
    }

    @PatchMapping(PATH + "/{tenantId}")
    public TenantDocument rename(
            @AuthenticationPrincipal OperatorPrincipal actor,
            @PathVariable("tenantId") String tenantId,
            @RequestBody RenameTenantRequest body) {
        TenantRecord updated = admin.rename(tenantId, body == null ? null : body.tenantName());
        audit.record(actor, "tenant.rename", updated.tenantId(), AuditOutcome.ALLOWED);
        return toDocument(updated);
    }

    @PostMapping(PATH + "/{tenantId}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(
            @AuthenticationPrincipal OperatorPrincipal actor, @PathVariable("tenantId") String tenantId) {
        admin.disable(tenantId);
        audit.record(actor, "tenant.disable", tenantId, AuditOutcome.ALLOWED);
    }

    @PostMapping(PATH + "/{tenantId}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enable(
            @AuthenticationPrincipal OperatorPrincipal actor, @PathVariable("tenantId") String tenantId) {
        admin.enable(tenantId);
        audit.record(actor, "tenant.enable", tenantId, AuditOutcome.ALLOWED);
    }

    private static TenantDocument toDocument(TenantRecord row) {
        return new TenantDocument(row.tenantId(), row.tenantName(), row.tenantState().name());
    }

    public record TenantsDocument(List<TenantDocument> tenants) {}

    public record TenantDocument(String tenantId, String tenantName, String tenantState) {}

    public record CreateTenantRequest(String tenantId, String tenantName) {}

    public record RenameTenantRequest(String tenantName) {}
}
