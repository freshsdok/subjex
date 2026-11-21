package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.idp.IdpExternalIdentity;

/**
 * EndUserWeChatAssertion — redeem a mini-program {@code jsCode} to a WeChat identity (WP2-4).
 * 把小程序 jsCode 兑换为微信身份。
 * <p>
 * Reuses the existing adapter model ({@code wechat:mini}, subject = unionId if present else openId).
 * Does not implement operator “unlinked = deny” policy.
 * 复用现有适配器模型；不实施操作员「未绑定即拒绝」策略。
 */
public interface EndUserWeChatAssertion {

    /** Whether this runtime can redeem WeChat mini-program codes — 当前是否可兑换小程序码。 */
    boolean configured();

    /**
     * Redeem {@code jsCode} via code2session (or a local/dev stub).
     * 通过 code2session（或本地/开发桩）兑换 jsCode。
     */
    IdpExternalIdentity assertMiniProgram(String jsCode);
}
