package com.subjex.platform.contract.payment;

import java.util.Currency;
import java.util.Locale;

/**
 * PaymentAmount — 金额：最小货币单位的非负整数 + 显式 ISO 4217 币种，不默认币种。
 * <p>
 * The caller must supply a real ISO 4217 code. Blank, short, or unknown currencies fail closed.
 * Negative minor units fail closed. The port does not convert currencies.
 * 调用方必须给出真实的 ISO 4217 币种。空白、过短或未知币种失败关闭。负的最小单位失败关闭。端口不做汇率兑换。
 */
public record PaymentAmount(long amountMinor, String currency) {

    public PaymentAmount {
        // 拒绝负金额 / Reject a negative amount
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amount must be a non-negative minor-unit integer");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency required");
        }
        String code = currency.trim().toUpperCase(Locale.ROOT);
        if (code.length() != 3) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code: " + currency);
        }
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown ISO 4217 currency: " + currency, ex);
        }
        currency = code;
    }
}
