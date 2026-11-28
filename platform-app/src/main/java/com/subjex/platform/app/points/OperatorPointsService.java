package com.subjex.platform.app.points;

import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.app.security.PolicyContext;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyPrincipal;
import com.subjex.platform.app.security.PolicyResource;
import com.subjex.platform.contract.points.AdjustPointsCommand;
import com.subjex.platform.contract.points.PointsAccountDocument;
import com.subjex.platform.contract.points.PointsActorKind;
import com.subjex.platform.contract.points.PointsDirection;
import com.subjex.platform.contract.points.PointsPort;
import com.subjex.platform.contract.points.PointsReference;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.util.Locale;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;

/**
 * OperatorPointsService — look up a subject ledger in a granted tenant, and grant or deduct with a reason.
 * 操作员在已授权租户内查阅主体台账，并按原因发放或扣减。
 */
public final class OperatorPointsService {

    private final PointsPort points;
    private final OperatorTenantAccess tenantAccess;
    private final PolicyEngine policyEngine;

    public OperatorPointsService(PointsPort points, OperatorTenantAccess tenantAccess, PolicyEngine policyEngine) {
        this.points = Objects.requireNonNull(points, "points");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
    }

    /**
     * Balance and newest-first history for one subject in the granted tenant.
     * 已授权租户内某一主体的余额与由新到旧的历史。
     */
    public PointsAccountDocument get(OperatorPrincipal operator, String tenantId, String subjectId, Integer limit) {
        String tid = requireOperatorAccess(operator, tenantId, OperatorPermission.POINTS_READ, "read");
        return points.getAccount(tid, subjectId, limit == null ? 0 : limit);
    }

    /**
     * Grant or deduct. The idempotency token is required and is compared to a command fingerprint.
     * 发放或扣减。幂等令牌必填，并与命令指纹对照。
     */
    public PointsAccountDocument adjust(
            OperatorPrincipal operator,
            String tenantId,
            String subjectId,
            String direction,
            Long amount,
            String reason,
            String memo,
            String referenceType,
            String referenceId,
            String idempotencyToken) {
        String tid = requireOperatorAccess(operator, tenantId, OperatorPermission.POINTS_ADJUST, "adjust");
        if (amount == null) {
            throw new IllegalArgumentException("amount must be a positive integer");
        }
        PointsReference reference = null;
        if ((referenceType != null && !referenceType.isBlank()) || (referenceId != null && !referenceId.isBlank())) {
            reference = new PointsReference(
                    referenceType == null ? "" : referenceType, referenceId == null ? "" : referenceId);
        }
        AdjustPointsCommand command = new AdjustPointsCommand(
                tid,
                subjectId,
                parseDirection(direction),
                amount,
                reason,
                memo,
                reference,
                idempotencyToken,
                operator.subjectId(),
                PointsActorKind.OPERATOR);
        return points.adjust(command);
    }

    private String requireOperatorAccess(
            OperatorPrincipal operator, String tenantId, OperatorPermission permission, String action) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String tid = tenantId.trim();
        if (JdbcPointsPort.RESERVED_TENANT.equals(tid)) {
            throw new AccessDeniedException("reserved-tenant");
        }
        tenantAccess.requireGranted(operator, tid);
        policyEngine.require(
                PolicyPrincipal.from(operator),
                permission.permissionName(),
                AccessAction.of(action),
                PolicyResource.of("points", "_").withAttribute(PolicyResource.ATTR_TENANT_ID, tid),
                PolicyContext.of(tid, false, OrganizationScope.unrestricted()));
        return tid;
    }

    private static PointsDirection parseDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("direction is required");
        }
        try {
            return PointsDirection.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown points direction: " + raw.trim());
        }
    }
}
