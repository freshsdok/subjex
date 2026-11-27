package com.subjex.platform.app.payment;

import com.subjex.platform.app.identity.enduser.EndUserAccountStore;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.contract.identity.Identity;
import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentNotFoundException;
import com.subjex.platform.contract.payment.PaymentStatus;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * EndUserPaymentService — list, inspect, and cancel the signed-in end user's own payments.
 * 终端用户查看并取消本人支付单：只能看见自己的单据，只能取消待支付。不能创单或扣费。
 */
public final class EndUserPaymentService {

    /** Realm stored on end-user payments — 终端付款人身份域. */
    public static final String PAYER_REALM = "END_USER";

    private final JdbcPaymentPort payments;
    private final EndUserAccountStore accounts;

    public EndUserPaymentService(JdbcPaymentPort payments, EndUserAccountStore accounts) {
        this.payments = Objects.requireNonNull(payments, "payments");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    /**
     * Own payments in the current tenant, newest first. Optional status filter.
     * 当前租户内本人单据，从新到旧；可按状态筛选。付款人不能改成别人。
     */
    public List<PaymentDocument> listOwn(
            EndUserPrincipal principal, String tenantId, String status, Integer limit) {
        Identity identity = requireMembership(principal, tenantId);
        return payments.listOrders(identity.tenantId(), parseStatus(status), identity.subjectId(), limit).stream()
                .filter(row -> ownedByEndUser(row, identity.subjectId()))
                .toList();
    }

    /**
     * One own payment, including refund lines. Another person's document is not found.
     * 本人的一张单据，含退款行。别人的单据按不存在处理。
     */
    public PaymentDocument getOwn(EndUserPrincipal principal, String tenantId, String paymentId) {
        Identity identity = requireMembership(principal, tenantId);
        PaymentDocument document = payments.queryOrder(identity.tenantId(), paymentId);
        if (!ownedByEndUser(document, identity.subjectId())) {
            throw new PaymentNotFoundException(identity.tenantId(), paymentId);
        }
        return document;
    }

    /**
     * Cancel a PENDING payment the caller owns. A repeat cancel is a no-op. Other statuses are refused.
     * 取消本人待支付单据。再次取消为空操作。其它状态一律拒绝。
     */
    public PaymentDocument cancelOwn(EndUserPrincipal principal, String tenantId, String paymentId) {
        PaymentDocument current = getOwn(principal, tenantId, paymentId);
        if (current.status() == PaymentStatus.CANCELLED) {
            return current;
        }
        if (current.status() != PaymentStatus.PENDING) {
            throw new PaymentCancelNotPendingException(current.tenantId(), current.paymentId());
        }
        try {
            return payments.cancelOrder(
                    current.tenantId(), current.paymentId(), current.payerSubjectId(), PAYER_REALM);
        } catch (IllegalStateException ex) {
            throw new PaymentCancelNotPendingException(current.tenantId(), current.paymentId());
        }
    }

    private Identity requireMembership(EndUserPrincipal principal, String tenantId) {
        Objects.requireNonNull(principal, "principal");
        return accounts.requireActiveMembership(principal.accountId(), tenantId);
    }

    private static boolean ownedByEndUser(PaymentDocument document, String subjectId) {
        if (document.payerSubjectId() == null || !document.payerSubjectId().equals(subjectId)) {
            return false;
        }
        String realm = document.payerRealm();
        return realm == null || realm.isBlank() || PAYER_REALM.equals(realm);
    }

    private static PaymentStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PaymentStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown payment status: " + raw.trim());
        }
    }
}
