package com.subjex.platform.app.idp;

/**
 * Callback payload — {@code code}+{@code state} for OAuth; {@code jsCode} for WeChat mini-program.
 * 回调载荷：OAuth 用 code+state；微信小程序用 jsCode。
 */
public record IdpCallbackRequest(String code, String state, String jsCode) {

    public static IdpCallbackRequest oauth(String code, String state) {
        return new IdpCallbackRequest(code, state, null);
    }

    public static IdpCallbackRequest miniProgram(String jsCode) {
        return new IdpCallbackRequest(null, null, jsCode);
    }
}
