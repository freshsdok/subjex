package com.subjex.platform.app.idp;

import com.subjex.platform.app.security.InvalidOperatorTokenException;
import com.subjex.platform.app.security.JdbcOidcLoginStore;
import com.subjex.platform.app.security.OidcPendingLogin;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * WeChatIdpAdapter — open-platform QR OAuth + mini-program code2session (ID-4).
 * Link-required / fail-closed (no auto-provision).
 * 微信开放平台扫码 + 小程序 code2session；须预先绑定，失败关闭。
 */
public final class WeChatIdpAdapter implements IdpAdapter {

    public static final String ID = "wechat";

    private final WeChatProperties properties;
    private final WeChatApiClient api;
    private final JdbcOidcLoginStore loginStore;

    public WeChatIdpAdapter(WeChatProperties properties, WeChatApiClient api, JdbcOidcLoginStore loginStore) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.api = Objects.requireNonNull(api, "api");
        this.loginStore = Objects.requireNonNull(loginStore, "loginStore");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String type() {
        return "wechat";
    }

    @Override
    public boolean enabled() {
        return properties.openReady() || properties.miniReady();
    }

    @Override
    public IdpAuthStart startAuth() {
        if (!properties.openReady()) {
            throw new IllegalStateException("wechat-open-disabled");
        }
        OidcPendingLogin pending = loginStore.create();
        String url = "https://open.weixin.qq.com/connect/qrconnect"
                + "?appid=" + enc(properties.appId())
                + "&redirect_uri=" + enc(properties.redirectUri())
                + "&response_type=code"
                + "&scope=snsapi_login"
                + "&state=" + enc(pending.state())
                + "#wechat_redirect";
        return new IdpAuthStart(url, pending.state());
    }

    @Override
    public IdpExternalIdentity handleCallback(IdpCallbackRequest request) {
        if (request == null) {
            throw new InvalidOperatorTokenException("wechat-callback-missing");
        }
        if (request.jsCode() != null && !request.jsCode().isBlank()) {
            return miniProgram(request.jsCode());
        }
        return openPlatform(request);
    }

    private IdpExternalIdentity openPlatform(IdpCallbackRequest request) {
        if (!properties.openReady()) {
            throw new IllegalStateException("wechat-open-disabled");
        }
        if (request.state() == null || request.state().isBlank()) {
            throw new InvalidOperatorTokenException("wechat-state-invalid");
        }
        loginStore
                .consume(request.state())
                .orElseThrow(() -> new InvalidOperatorTokenException("wechat-state-invalid"));
        WeChatApiClient.WeChatSession session = api.redeemOpenCode(request.code());
        String subject = session.subject();
        if (subject.isBlank()) {
            throw new InvalidOperatorTokenException("wechat-subject-missing");
        }
        return new IdpExternalIdentity(ID, WeChatProperties.ISSUER_OPEN, subject, null);
    }

    private IdpExternalIdentity miniProgram(String jsCode) {
        if (!properties.miniReady()) {
            throw new IllegalStateException("wechat-mini-disabled");
        }
        WeChatApiClient.WeChatSession session = api.code2Session(jsCode);
        String subject = session.subject();
        if (subject.isBlank()) {
            throw new InvalidOperatorTokenException("wechat-subject-missing");
        }
        return new IdpExternalIdentity(ID, WeChatProperties.ISSUER_MINI, subject, null);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
