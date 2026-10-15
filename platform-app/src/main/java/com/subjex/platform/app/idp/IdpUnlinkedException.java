package com.subjex.platform.app.idp;

/**
 * IdpUnlinkedException — verified IdP identity has no operator_idp_link (fail-closed).
 * 已校验的 IdP 身份无绑定（失败关闭）。
 */
public final class IdpUnlinkedException extends RuntimeException {

    private final String providerId;
    private final String issuer;
    private final String idpSubject;
    private final String email;

    public IdpUnlinkedException(String providerId, String issuer, String idpSubject, String email) {
        super("idp-unlinked");
        this.providerId = providerId;
        this.issuer = issuer;
        this.idpSubject = idpSubject;
        this.email = email;
    }

    public String providerId() {
        return providerId;
    }

    public String issuer() {
        return issuer;
    }

    public String idpSubject() {
        return idpSubject;
    }

    public String email() {
        return email;
    }
}
