package com.subjex.platform.app.payment;

/**
 * PaymentProviderSettings — explicit adapter choice and HTTP/merchant placeholders for one provider.
 * 单一供应商的显式适配器选择与 HTTP/商户占位配置。
 * <p>
 * Values are placeholders until a merchant account exists. An incomplete HTTP shape is not a live
 * vendor and must fail closed.
 * 在商户号到位前这些只是占位。HTTP 配置不完整不是已启用的真实渠道，必须失败关闭。
 */
public record PaymentProviderSettings(
        String adapter, String httpBaseUrl, String httpApiKey, String httpMerchantId) {

    public static PaymentProviderSettings empty() {
        return new PaymentProviderSettings("", "", "", "");
    }
}
