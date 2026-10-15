package com.subjex.platform.app.idp;

/**
 * WeChatApiClient — HTTP to WeChat OAuth / jscode2session (mockable in tests).
 * 微信 OAuth / jscode2session HTTP（测试可桩）。
 */
public interface WeChatApiClient {

    /** Exchange open-platform OAuth code → openid (+ optional unionid). */
    WeChatSession redeemOpenCode(String code);

    /** Mini-program js_code → openid (+ optional unionid). */
    WeChatSession code2Session(String jsCode);

    record WeChatSession(String openId, String unionId) {
        /** Prefer unionid when present for stable cross-app subject — 有 unionid 时优先作主体。 */
        public String subject() {
            if (unionId != null && !unionId.isBlank()) {
                return unionId.trim();
            }
            return openId == null ? "" : openId.trim();
        }
    }
}
