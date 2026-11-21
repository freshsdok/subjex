package com.subjex.platform.contract.payment;

/**
 * PaymentProviderIds — 支付供应商标识：仅名称，本包不绑定某一商业 SDK。
 * <p>
 * {@link #MOCK} is the phase-1 adapter. {@link #WECHAT_PAY} and {@link #ALIPAY} are reserved
 * names only; this package does not add their SDKs or pick a product.
 * {@link #MOCK} 是首期适配器。{@link #WECHAT_PAY} 与 {@link #ALIPAY} 只是预留名称；本包不引入其 SDK，也不选定产品形态。
 */
public final class PaymentProviderIds {

    /** Phase-1 mock / dev adapter id / 首期模拟或开发适配器标识 */
    public static final String MOCK = "MOCK";

    /** Reserved identifier only; no SDK here / 仅预留标识，此处无 SDK */
    public static final String WECHAT_PAY = "WECHAT_PAY";

    /** Reserved identifier only; no SDK here / 仅预留标识，此处无 SDK */
    public static final String ALIPAY = "ALIPAY";

    private PaymentProviderIds() {}
}
