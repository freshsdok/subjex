package com.subjex.platform.app.consent;

import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.app.security.PolicyContext;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyPrincipal;
import com.subjex.platform.app.security.PolicyResource;
import com.subjex.platform.contract.consent.ConsentEvent;
import com.subjex.platform.contract.consent.ConsentPort;
import com.subjex.platform.contract.consent.ConsentRecord;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * OperatorConsentService — read-only operator query of purpose-consent records in a granted tenant.
 * 操作员在已授权租户内只读查询目的同意记录；不能代为授予或撤回。
 */
public final class OperatorConsentService {

    private final ConsentPort consents;
    private final OperatorTenantAccess tenantAccess;
    private final PolicyEngine policyEngine;

    public OperatorConsentService(
            ConsentPort consents, OperatorTenantAccess tenantAccess, PolicyEngine policyEngine) {
        this.consents = Objects.requireNonNull(consents, "consents");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
    }

    /**
     * Current grants, filtered and newest-first.
     * 当前有效授予，可筛选，按时间从新到旧。
     */
    public List<ConsentRecord> listCurrent(
            OperatorPrincipal operator,
            String tenantId,
            String subjectId,
            String purposeKey,
            Instant grantedFrom,
            Instant grantedTo) {
        String tid = requireOperatorAccess(operator, tenantId);
        return consents.searchCurrent(tid, subjectId, purposeKey, grantedFrom, grantedTo, ConsentPort.DEFAULT_SEARCH_LIMIT);
    }

    /**
     * Append-only history including withdrawals, filtered and newest-first.
     * 含撤回的追加式历史，可筛选，按时间从新到旧。
     */
    public List<ConsentEvent> history(
            OperatorPrincipal operator,
            String tenantId,
            String subjectId,
            String purposeKey,
            Instant from,
            Instant to) {
        String tid = requireOperatorAccess(operator, tenantId);
        return consents.searchHistory(tid, subjectId, purposeKey, from, to, ConsentPort.DEFAULT_SEARCH_LIMIT);
    }

    private String requireOperatorAccess(OperatorPrincipal operator, String tenantId) {
        // Tenant fail-closed lives in ConsentPort; grant and permission are checked here.
        // 租户失败关闭由 ConsentPort 负责；此处核对租户授权与阅读权限。
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String tid = tenantId.trim();
        tenantAccess.requireGranted(operator, tid);
        policyEngine.require(
                PolicyPrincipal.from(operator),
                OperatorPermission.CONSENT_READ.permissionName(),
                AccessAction.of("read"),
                PolicyResource.of("consent", "_").withAttribute(PolicyResource.ATTR_TENANT_ID, tid),
                PolicyContext.of(tid, false, OrganizationScope.unrestricted()));
        return tid;
    }
}
