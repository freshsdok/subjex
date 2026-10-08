package com.subjex.platform.app.security;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * OperatorManagementEndpoint — 操作员管理 JSON：改密、列表/创建、禁用/启用、租户授权。
 * <p>
 * Self password change is authenticated-only (current password required). Other routes need
 * {@code operator.manage}. Bootstrap one-shot remains available and optional.
 * 自己改密只需已登录（并核对当前口令）。其它路由要 {@code operator.manage}。一次性开通仍可用且可选。
 */
@RestController
public class OperatorManagementEndpoint {

    public static final String PATH = JsonApi.BASE + "/operators";

    private final JdbcOperatorAdmin admin;
    private final OperatorActionAudit audit;
    private final JdbcOperatorTokenStore tokenStore;

    public OperatorManagementEndpoint(
            JdbcOperatorAdmin admin, OperatorActionAudit audit, JdbcOperatorTokenStore tokenStore) {
        this.admin = admin;
        this.audit = audit;
        this.tokenStore = tokenStore;
    }

    @GetMapping(PATH)
    public OperatorsDocument list() {
        List<OperatorDocument> operators = admin.list().stream()
                .map(row -> new OperatorDocument(
                        row.loginName(), row.accountState(), row.subjectId(), row.identityId(), row.roleName()))
                .toList();
        return new OperatorsDocument(operators);
    }

    @PostMapping(PATH)
    @ResponseStatus(HttpStatus.CREATED)
    public OperatorDocument create(
            @AuthenticationPrincipal OperatorPrincipal actor, @RequestBody CreateOperatorRequest body) {
        JdbcOperatorAdmin.OperatorSummary created =
                admin.create(body == null ? null : body.loginName(), body == null ? null : body.password(),
                        body == null ? null : body.roleName());
        audit.record(actor, "operator.create", created.loginName(), AuditOutcome.ALLOWED);
        return new OperatorDocument(
                created.loginName(),
                created.accountState(),
                created.subjectId(),
                created.identityId(),
                created.roleName());
    }

    @PostMapping(PATH + "/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeOwnPassword(
            @AuthenticationPrincipal OperatorPrincipal self, @RequestBody ChangeOwnPasswordRequest body) {
        admin.changeOwnPassword(
                self, body == null ? null : body.currentPassword(), body == null ? null : body.newPassword());
        tokenStore.revokeAllForSubject(self.subjectId());
        audit.record(self, "operator.password.change-self", self.getUsername(), AuditOutcome.ALLOWED);
    }

    @PostMapping(PATH + "/{loginName}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeOtherPassword(
            @AuthenticationPrincipal OperatorPrincipal actor,
            @PathVariable("loginName") String loginName,
            @RequestBody ChangePasswordRequest body) {
        admin.changePassword(loginName, body == null ? null : body.newPassword());
        tokenStore.revokeAllForSubject(admin.requireSubjectId(loginName));
        audit.record(actor, "operator.password.change", loginName, AuditOutcome.ALLOWED);
    }

    @PostMapping(PATH + "/{loginName}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(
            @AuthenticationPrincipal OperatorPrincipal actor, @PathVariable("loginName") String loginName) {
        String subjectId = admin.requireSubjectId(loginName);
        admin.disable(actor, loginName);
        tokenStore.revokeAllForSubject(subjectId);
        audit.record(actor, "operator.disable", loginName, AuditOutcome.ALLOWED);
    }

    @PostMapping(PATH + "/{loginName}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enable(
            @AuthenticationPrincipal OperatorPrincipal actor, @PathVariable("loginName") String loginName) {
        admin.enable(loginName);
        audit.record(actor, "operator.enable", loginName, AuditOutcome.ALLOWED);
    }

    @GetMapping(PATH + "/{loginName}/tenants")
    public TenantGrantsDocument listTenants(@PathVariable("loginName") String loginName) {
        return new TenantGrantsDocument(loginName, admin.listTenantGrants(loginName));
    }

    @PutMapping(PATH + "/{loginName}/tenants")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replaceTenants(
            @AuthenticationPrincipal OperatorPrincipal actor,
            @PathVariable("loginName") String loginName,
            @RequestBody TenantGrantsRequest body) {
        List<String> tenants = body == null || body.tenantIds() == null ? List.of() : body.tenantIds();
        admin.replaceTenantGrants(loginName, tenants);
        audit.record(actor, "operator.tenants.replace", loginName, AuditOutcome.ALLOWED);
    }

    public record OperatorsDocument(List<OperatorDocument> operators) {}

    public record OperatorDocument(
            String loginName, String accountState, String subjectId, String identityId, String roleName) {}

    public record CreateOperatorRequest(String loginName, String password, String roleName) {}

    public record ChangeOwnPasswordRequest(String currentPassword, String newPassword) {}

    public record ChangePasswordRequest(String newPassword) {}

    public record TenantGrantsDocument(String loginName, List<String> tenantIds) {}

    public record TenantGrantsRequest(List<String> tenantIds) {}
}
