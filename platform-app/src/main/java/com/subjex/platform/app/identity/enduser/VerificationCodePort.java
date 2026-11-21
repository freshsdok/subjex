package com.subjex.platform.app.identity.enduser;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * VerificationCodePort — generic short-lived phone verification codes (ADR 0014 / WP2-3).
 * 通用短时手机验证码端口。内核不含短信厂商。
 * <p>
 * Local/dev adapters may reveal a code to tests or a laptop response. Production must use a
 * configured sender and must not print or return codes.
 * 本地/开发适配器可向测试或本机响应出示验证码。生产必须配置真正的发送器，不得打印或返回验证码。
 */
public interface VerificationCodePort {

    /** Login or first-time register — 登录或首次注册。 */
    String PURPOSE_LOGIN = "phone-login";

    /** Bind a phone to an authenticated account — 已认证账号绑定手机号。 */
    String PURPOSE_BIND = "phone-bind";

    /** Prove possession before unbind — 解绑前证明持有该号码。 */
    String PURPOSE_UNBIND = "phone-unbind";

    Duration ttl();

    /**
     * Whether issue responses may include the plaintext code (local/dev only).
     * 签发响应是否可带明文验证码（仅限本地/开发）。
     */
    boolean revealInResponse();

    /**
     * Issue a new code, replacing any previous one for the same destination and purpose.
     * 签发新验证码，覆盖同一目的地与用途的旧码。
     */
    IssuedCode issue(String destination, String purpose);

    /**
     * Verify and consume a live code. Expired, unknown, or already-used codes fail closed.
     * 校验并消费仍有效的验证码。过期、未知或已使用则失败关闭。
     */
    boolean verifyAndConsume(String destination, String purpose, String code);

    /**
     * Dev/test hook to read the current plaintext code, if the adapter keeps one.
     * 开发/测试读取当前明文验证码（若适配器保留）。
     */
    Optional<String> peekForDev(String destination, String purpose);

    static String requirePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("verification purpose is required");
        }
        String value = purpose.trim().toLowerCase(Locale.ROOT);
        if (!PURPOSE_LOGIN.equals(value)
                && !PURPOSE_BIND.equals(value)
                && !PURPOSE_UNBIND.equals(value)) {
            throw new IllegalArgumentException("unknown verification purpose: " + purpose);
        }
        return value;
    }

    record IssuedCode(String destination, String purpose, long expiresInSeconds, String devCode) {}
}
