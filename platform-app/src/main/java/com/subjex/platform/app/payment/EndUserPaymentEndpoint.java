package com.subjex.platform.app.payment;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.EndUserPrincipals;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.payment.PaymentChargeSession;
import com.subjex.platform.contract.payment.PaymentDocument;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EndUserPaymentEndpoint — own payments under {@code /api/v1/end-user/payments}:
 * create, start charge, list, status, cancel PENDING.
 * 终端本人支付单：创单、发起扣款、列出、查看状态、取消待支付。不是货品目录或购物车。
 */
@RestController
public class EndUserPaymentEndpoint {

    /** JSON base for the end-user payment page — 终端「我的支付」JSON 根路径. */
    public static final String PATH = JsonApi.BASE + "/end-user/payments";

    private final EndUserPaymentService payments;

    public EndUserPaymentEndpoint(EndUserPaymentService payments) {
        this.payments = Objects.requireNonNull(payments, "payments");
    }

    @GetMapping(PATH)
    public List<PaymentDocument> list(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        return payments.listOwn(principal, resolveTenant(tenantHeader, tenantIdParam), status, limit);
    }

    @GetMapping(PATH + "/{paymentId}")
    public PaymentDocument get(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("paymentId") String paymentId) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        return payments.getOwn(principal, resolveTenant(tenantHeader, tenantIdParam), paymentId);
    }

    @PostMapping(PATH + "/{paymentId}/cancel")
    public PaymentDocument cancel(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("paymentId") String paymentId) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        return payments.cancelOwn(principal, resolveTenant(tenantHeader, tenantIdParam), paymentId);
    }

    @PostMapping(PATH)
    public PaymentDocument create(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = "X-Idempotency-Token", required = false) String idempotencyHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody(required = false) CreateRequest body) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        CreateRequest request = body == null ? new CreateRequest(null, null, null, null, null) : body;
        String token = firstNonBlank(idempotencyHeader, request.idempotencyKey());
        Reference reference = request.reference();
        return payments.createOwn(
                principal,
                resolveTenant(tenantHeader, tenantIdParam),
                request.amountMinor(),
                request.currency(),
                reference == null ? null : reference.type(),
                reference == null ? null : reference.id(),
                request.memo(),
                token);
    }

    @PostMapping(PATH + "/{paymentId}/charge")
    public PaymentChargeSession charge(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("paymentId") String paymentId) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        return payments.startChargeOwn(principal, resolveTenant(tenantHeader, tenantIdParam), paymentId);
    }

    /**
     * Create body: amount, currency, type+id reference, optional memo and idempotency key.
     * 创单请求体：金额、币种、类型+标识引用，以及可选备注与幂等键。
     */
    public record CreateRequest(
            Long amountMinor,
            String currency,
            Reference reference,
            String memo,
            String idempotencyKey) {}

    /** Business reference tokens, not a closed catalog — 业务引用令牌，不是封闭目录. */
    public record Reference(String type, String id) {}

    private static String firstNonBlank(String header, String body) {
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (body != null && !body.isBlank()) {
            return body.trim();
        }
        return null;
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
