package com.subjex.platform.app.payment;

import com.subjex.platform.app.identity.enduser.EndUserAccountStore;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.contract.identity.Identity;
import com.subjex.platform.contract.payment.CreatePaymentCommand;
import com.subjex.platform.contract.payment.PaymentAmount;
import com.subjex.platform.contract.payment.PaymentChargeSession;
import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentNotFoundException;
import com.subjex.platform.contract.payment.PaymentStatus;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * EndUserPaymentService — the signed-in end user can create, start charge, list, inspect, and cancel own payments.
 * 已登录终端用户可创单、发起扣款、列出、查看并取消本人支付单。付款人不能改成别人。
 */
public final class EndUserPaymentService {

    /** Realm stored on end-user payments — 终端付款人身份域. */
    public static final String PAYER_REALM = "END_USER";

    /** Same token shape as resource_event types — 与 resource_event 类型相同的令牌形态. */
    static final Pattern REFERENCE_TYPE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9._:-]{0,63}$");

    /** Same token shape as resource_event ids — 与 resource_event 标识相同的令牌形态. */
    static final Pattern REFERENCE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    static final int MEMO_MAX_LENGTH = 256;
    static final int IDEMPOTENCY_MAX_LENGTH = 128;

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

    /**
     * Create a payment for the signed-in membership subject. Payer cannot be someone else.
     * 为当前成员主体创单。付款人不能写成别人。
     */
    public PaymentDocument createOwn(
            EndUserPrincipal principal,
            String tenantId,
            Long amountMinor,
            String currency,
            String referenceType,
            String referenceId,
            String memo,
            String idempotencyToken) {
        Identity identity = requireMembership(principal, tenantId);
        String token = requireIdempotencyToken(idempotencyToken);
        if (amountMinor == null || amountMinor < 1L) {
            throw new IllegalArgumentException("amount must be a positive minor-unit integer");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency required");
        }
        String type = requireToken(referenceType, REFERENCE_TYPE_PATTERN, "reference.type");
        String id = requireToken(referenceId, REFERENCE_ID_PATTERN, "reference.id");
        String note = normalizeMemo(memo);
        PaymentAmount amount = new PaymentAmount(amountMinor, currency);
        String reference = JdbcPaymentPort.composeReference(type, id);
        return payments.createOrder(new CreatePaymentCommand(
                identity.tenantId(),
                amount,
                reference,
                identity.subjectId(),
                PAYER_REALM,
                token,
                payments.defaultProviderId(),
                note));
    }

    /**
     * Start charge on a PENDING payment the caller owns. Does not mark SUCCEEDED.
     * 对本人待支付单据发起扣款。不把状态写成成功。
     */
    public PaymentChargeSession startChargeOwn(EndUserPrincipal principal, String tenantId, String paymentId) {
        PaymentDocument current = getOwn(principal, tenantId, paymentId);
        if (current.status() != PaymentStatus.PENDING) {
            throw new PaymentChargeNotPendingException(current.tenantId(), current.paymentId());
        }
        try {
            return payments.startCharge(
                    current.tenantId(), current.paymentId(), current.payerSubjectId(), PAYER_REALM);
        } catch (IllegalStateException ex) {
            throw new PaymentChargeNotPendingException(current.tenantId(), current.paymentId());
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

    private static String requireIdempotencyToken(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("idempotency token is missing");
        }
        String token = raw.trim();
        if (token.length() > IDEMPOTENCY_MAX_LENGTH) {
            throw new IllegalArgumentException("idempotency token is too long");
        }
        return token;
    }

    private static String requireToken(String raw, Pattern pattern, String label) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        String token = raw.trim();
        if (!pattern.matcher(token).matches()) {
            throw new IllegalArgumentException(label + " is not a valid token");
        }
        return token;
    }

    private static String normalizeMemo(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String memo = raw.trim();
        if (memo.length() > MEMO_MAX_LENGTH) {
            throw new IllegalArgumentException("memo is too long");
        }
        return memo;
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
