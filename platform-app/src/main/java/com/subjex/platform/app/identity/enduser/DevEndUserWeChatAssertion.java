package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.idp.IdpExternalIdentity;
import com.subjex.platform.app.idp.WeChatApiClient;
import com.subjex.platform.app.idp.WeChatIdpAdapter;
import com.subjex.platform.app.idp.WeChatProperties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DevEndUserWeChatAssertion — in-process stub for tests and the {@code local} profile (WP2-4).
 * 测试与 {@code local} 用的进程内桩，不调用微信 HTTP。
 * <p>
 * A mapped {@code jsCode} wins. Otherwise {@code dev:{openId}} or {@code dev:{openId}:{unionId}}
 * is accepted (unionId is preferred as {@code idp_subject}, same as {@link WeChatApiClient.WeChatSession#subject()}).
 * 已登记的 jsCode 优先；否则接受 {@code dev:{openId}} 或 {@code dev:{openId}:{unionId}}（有 unionId 时优先作主体）。
 */
public final class DevEndUserWeChatAssertion implements EndUserWeChatAssertion {

    private final ConcurrentHashMap<String, WeChatApiClient.WeChatSession> mapped = new ConcurrentHashMap<>();

    @Override
    public boolean configured() {
        return true;
    }

    /** Map a jsCode to a session for tests — 为测试登记 jsCode 与会话。 */
    public DevEndUserWeChatAssertion map(String jsCode, String openId, String unionId) {
        if (jsCode == null || jsCode.isBlank()) {
            throw new IllegalArgumentException("jsCode is required");
        }
        mapped.put(jsCode.trim(), new WeChatApiClient.WeChatSession(openId, unionId));
        return this;
    }

    @Override
    public IdpExternalIdentity assertMiniProgram(String jsCode) {
        if (jsCode == null || jsCode.isBlank()) {
            throw new InvalidEndUserWeChatException();
        }
        String raw = jsCode.trim();
        WeChatApiClient.WeChatSession session = mapped.get(raw);
        if (session == null) {
            session = parseDevCode(raw);
        }
        if (session == null) {
            throw new InvalidEndUserWeChatException();
        }
        String subject = session.subject();
        if (subject.isBlank()) {
            throw new InvalidEndUserWeChatException();
        }
        return new IdpExternalIdentity(WeChatIdpAdapter.ID, WeChatProperties.ISSUER_MINI, subject, null);
    }

    private static WeChatApiClient.WeChatSession parseDevCode(String raw) {
        if (!raw.startsWith("dev:")) {
            return null;
        }
        String rest = raw.substring("dev:".length());
        if (rest.isBlank()) {
            return null;
        }
        int split = rest.indexOf(':');
        if (split < 0) {
            return new WeChatApiClient.WeChatSession(rest, null);
        }
        String openId = rest.substring(0, split);
        String unionId = rest.substring(split + 1);
        return new WeChatApiClient.WeChatSession(openId, unionId.isBlank() ? null : unionId);
    }
}
