package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.idp.IdpCallbackRequest;
import com.subjex.platform.app.idp.IdpExternalIdentity;
import com.subjex.platform.app.idp.WeChatIdpAdapter;
import com.subjex.platform.app.idp.WeChatProperties;
import com.subjex.platform.app.security.InvalidOperatorTokenException;
import java.util.Objects;

/**
 * AdapterEndUserWeChatAssertion — redeems jsCode through the existing {@link WeChatIdpAdapter}.
 * 通过现有 {@link WeChatIdpAdapter} 兑换 jsCode，不改操作员「未绑定即拒绝」编排。
 */
public final class AdapterEndUserWeChatAssertion implements EndUserWeChatAssertion {

    private final WeChatIdpAdapter adapter;
    private final WeChatProperties properties;

    public AdapterEndUserWeChatAssertion(WeChatIdpAdapter adapter, WeChatProperties properties) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public boolean configured() {
        return properties.miniReady();
    }

    @Override
    public IdpExternalIdentity assertMiniProgram(String jsCode) {
        if (!configured()) {
            throw new WeChatUnconfiguredException();
        }
        if (jsCode == null || jsCode.isBlank()) {
            throw new InvalidEndUserWeChatException();
        }
        try {
            return adapter.handleCallback(IdpCallbackRequest.miniProgram(jsCode));
        } catch (InvalidOperatorTokenException ex) {
            throw new InvalidEndUserWeChatException(ex.getMessage());
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("wechat-mini-disabled")) {
                throw new WeChatUnconfiguredException();
            }
            throw ex;
        }
    }
}
