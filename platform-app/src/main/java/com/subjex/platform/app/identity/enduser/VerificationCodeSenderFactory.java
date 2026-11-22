package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.security.ProductionProfileGuard;
import com.subjex.platform.contract.delivery.SecretPlaceholderGuard;
import java.net.URI;
import java.util.Locale;

/**
 * VerificationCodeSenderFactory — selects the SMS OTP sender from explicit configuration.
 * 按显式配置选择短信验证码发送器。
 * <p>
 * Production defaults to fail-closed. {@code sender=dev} is refused under prod/production.
 * {@code sender=http} uses the vendor-shaped HTTP template only when the base URL and API key
 * are complete; missing or placeholder secrets fail closed and never fall back to the dev sender.
 * 生产默认失败关闭。生产禁止 {@code sender=dev}。{@code sender=http} 仅在端点与密钥配齐时启用厂商形
 * HTTP 模板；缺配或占位密钥失败关闭，绝不回退到开发发送器。
 */
public final class VerificationCodeSenderFactory {

    /** Fail-closed stand-in — 失败关闭占位。 */
    public static final String SENDER_FAIL_CLOSED = "fail-closed";

    /** Dev no-op sender (non-production only) — 开发空发送器（仅非生产）。 */
    public static final String SENDER_DEV = "dev";

    /** Vendor-shaped HTTP template — 厂商形 HTTP 模板。 */
    public static final String SENDER_HTTP = "http";

    private VerificationCodeSenderFactory() {}

    /**
     * Build the sender for the active profiles and configured values.
     * 按活动 profile 与已配值构造发送器。
     */
    public static VerificationCodeSender create(
            String[] activeProfiles,
            boolean revealInResponse,
            String sender,
            String httpBaseUrl,
            String httpApiKey,
            String httpFrom,
            VerificationCodeHttpClient httpClient) {
        boolean production = ProductionProfileGuard.isProductionProfile(activeProfiles);
        if (production && revealInResponse) {
            throw new IllegalStateException(
                    "platform.end-user.auth.verification-code.reveal-in-response must stay false under prod/production");
        }
        String selected = normalize(sender);
        if (production && SENDER_DEV.equals(selected)) {
            throw new IllegalStateException(
                    "platform.end-user.auth.verification-code.sender=dev is not allowed under prod/production");
        }
        if (SENDER_HTTP.equals(selected)) {
            if (httpConfigured(httpBaseUrl, httpApiKey) && httpClient != null) {
                return new HttpVerificationCodeSender(
                        httpClient, httpBaseUrl.trim(), httpApiKey.trim(), blankToNull(httpFrom));
            }
            return new FailClosedVerificationCodeSender();
        }
        if (SENDER_FAIL_CLOSED.equals(selected) || (production && selected.isEmpty())) {
            return new FailClosedVerificationCodeSender();
        }
        if (!production && (selected.isEmpty() || SENDER_DEV.equals(selected))) {
            return new DevVerificationCodeSender();
        }
        return new FailClosedVerificationCodeSender();
    }

    static boolean httpConfigured(String httpBaseUrl, String httpApiKey) {
        if (isBlank(httpBaseUrl)
                || isBlank(httpApiKey)
                || SecretPlaceholderGuard.looksLikePlaceholder(httpApiKey)) {
            return false;
        }
        try {
            URI uri = URI.create(httpBaseUrl.trim());
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                    && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String normalize(String sender) {
        return sender == null ? "" : sender.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim();
    }

}
