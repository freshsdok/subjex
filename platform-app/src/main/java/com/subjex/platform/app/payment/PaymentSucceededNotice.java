package com.subjex.platform.app.payment;

import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationCommand;
import com.subjex.platform.contract.notification.NotificationRecipient;
import com.subjex.platform.contract.payment.PaymentStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * PaymentSucceededNotice — platform-generic template for a payer notice when a payment becomes SUCCEEDED.
 * 支付成功后发给付款人的平台通用模板：金额、币种、单据号与状态；不含商户目录或行业文案。
 * <p>
 * The kernel stores the template id and parameter map only. Delivery uses the existing email
 * channel adapter (receipt path); it is not a choice of SMS or WeChat product. When that adapter
 * is unconfigured, enqueue still writes {@code NotificationRequested} and dispatch fail-closes.
 * 内核只保存模板标识与参数。投递走已有邮件渠道（回执路径），不在此挑选短信或微信产品。
 * 该渠道未配置时仍写入出箱，调度失败关闭。
 */
public final class PaymentSucceededNotice {

    /** Template id stored on the outbox row — 写入出箱的模板标识. */
    public static final String TEMPLATE_ID = "payment.succeeded";

    /**
     * Receipt channel for this platform notice. Not a vendor-product pick; other channels are unchanged.
     * 本通知的回执渠道。不是厂商产品选型；其它渠道保持原样。
     */
    public static final NotificationChannel CHANNEL = NotificationChannel.EMAIL;

    public static final String PARAM_PAYMENT_ID = "paymentId";
    public static final String PARAM_AMOUNT_MINOR = "amountMinor";
    public static final String PARAM_CURRENCY = "currency";
    public static final String PARAM_STATUS = "status";

    private PaymentSucceededNotice() {}

    /**
     * Build the enqueue command addressed to the payer subject in the payment tenant.
     * 构造入队命令：在支付租户内按付款人主体寻址。
     */
    public static NotificationCommand command(
            String tenantId,
            String paymentId,
            long amountMinor,
            String currency,
            PaymentStatus status,
            InboxRecipientRealm payerRealm,
            String payerSubjectId) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(payerRealm, "payerRealm");
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(PARAM_PAYMENT_ID, paymentId);
        parameters.put(PARAM_AMOUNT_MINOR, Long.toString(amountMinor));
        parameters.put(PARAM_CURRENCY, currency);
        parameters.put(PARAM_STATUS, status.name());
        return new NotificationCommand(
                tenantId,
                TEMPLATE_ID,
                CHANNEL,
                NotificationRecipient.ofSubject(payerRealm, payerSubjectId, tenantId),
                parameters);
    }
}
