package com.subjex.platform.app.security;

/**
 * OidcUnlinkedException — IdP 身份未绑定到任何操作员（默认拒绝、不自动建号）。
 */
public final class OidcUnlinkedException extends RuntimeException {

    private final String issuer;
    private final String idpSubject;
    private final String email;

    public OidcUnlinkedException(String issuer, String idpSubject, String email) {
        super("oidc-unlinked");
        this.issuer = issuer;
        this.idpSubject = idpSubject;
        this.email = email;
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
