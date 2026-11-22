package com.subjex.platform.app.payment;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.payment.PaymentDocument;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OperatorPaymentEndpoint — read-only payment and refund status under {@code /api/v1/payments}.
 * 操作员只读支付与退款状态；无创单、回调或退款路由，不做结账。
 */
@RestController
public class OperatorPaymentEndpoint {

    /** JSON base for operator payment queries — 操作员支付查询 JSON 根路径. */
    public static final String PATH = JsonApi.BASE + "/payments";

    private final OperatorPaymentService payments;

    /**
     * Single constructor so Spring can wire the bean.
     * 只保留一个构造器，避免 Spring 因重载而拒绝启动。
     */
    public OperatorPaymentEndpoint(OperatorPaymentService payments) {
        this.payments = Objects.requireNonNull(payments, "payments");
    }

    @GetMapping(PATH)
    public List<PaymentDocument> list(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "payerSubjectId", required = false) String payerSubjectId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return payments.list(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), status, payerSubjectId, limit);
    }

    @GetMapping(PATH + "/{paymentId}")
    public PaymentDocument get(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("paymentId") String paymentId) {
        return payments.get(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), paymentId);
    }

    private static OperatorPrincipal requireOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof OperatorPrincipal operator) {
            return operator;
        }
        throw new org.springframework.security.access.AccessDeniedException("operator-authentication-required");
    }

    private static String resolveTenant(String header, String param) {
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (param != null && !param.isBlank()) {
            return param.trim();
        }
        return null;
    }
}
