package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.idp.IdpExternalIdentity;

/**
 * FailClosedEndUserWeChatAssertion — production stand-in when WeChat mini-program is not configured.
 * 生产占位：未配置小程序时失败关闭，绝不兑换或自动开户。
 */
public final class FailClosedEndUserWeChatAssertion implements EndUserWeChatAssertion {

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public IdpExternalIdentity assertMiniProgram(String jsCode) {
        throw new WeChatUnconfiguredException();
    }
}
