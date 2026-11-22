package com.subjex.platform.app.payment;

import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.app.security.PolicyContext;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyPrincipal;
import com.subjex.platform.app.security.PolicyResource;
import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentStatus;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;

/**
 * OperatorPaymentService — read-only operator query of payment and refund status in a granted tenant.
 * 操作员在已授权租户内只读查询支付与退款状态；不能创单、办理回调或发起退款。
 */
public final class OperatorPaymentService {

    private final JdbcPaymentPort payments;
    private final OperatorTenantAccess tenantAccess;
    private final PolicyEngine policyEngine;

    public OperatorPaymentService(
            JdbcPaymentPort payments, OperatorTenantAccess tenantAccess, PolicyEngine policyEngine) {
        this.payments = Objects.requireNonNull(payments, "payments");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
    }

    /**
     * Tenant-scoped list, newest first, with optional status and payer filters.
     * 当前租户内列表，按时间从新到旧，可按状态与付款人筛选。
     */
    public List<PaymentDocument> list(
            OperatorPrincipal operator, String tenantId, String status, String payerSubjectId, Integer limit) {
        String tid = requireOperatorAccess(operator, tenantId);
        return payments.listOrders(tid, parseStatus(status), payerSubjectId, limit);
    }

    /**
     * One document via {@link com.subjex.platform.contract.payment.PaymentPort#queryOrder}; includes refund lines.
     * 经已有查单接口取一张单据，含退款行。
     */
    public PaymentDocument get(OperatorPrincipal operator, String tenantId, String paymentId) {
        String tid = requireOperatorAccess(operator, tenantId);
        return payments.queryOrder(tid, paymentId);
    }

    private String requireOperatorAccess(OperatorPrincipal operator, String tenantId) {
        // Reserved platform is not a business payment tenant; fail closed as 403 for operators.
        // 保留租户 platform 不是业务支付租户；操作员侧失败关闭为 403。
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String tid = tenantId.trim();
        if (JdbcPaymentPort.RESERVED_TENANT.equals(tid)) {
            throw new AccessDeniedException("reserved-tenant");
        }
        tenantAccess.requireGranted(operator, tid);
        policyEngine.require(
                PolicyPrincipal.from(operator),
                OperatorPermission.PAYMENT_READ.permissionName(),
                AccessAction.of("read"),
                PolicyResource.of("payment", "_").withAttribute(PolicyResource.ATTR_TENANT_ID, tid),
                PolicyContext.of(tid, false, OrganizationScope.unrestricted()));
        return tid;
    }

    private static PaymentStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return PaymentStatus.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown payment status: " + raw.trim());
        }
    }
}
