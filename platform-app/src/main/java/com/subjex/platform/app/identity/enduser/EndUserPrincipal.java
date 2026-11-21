package com.subjex.platform.app.identity.enduser;

import java.util.Objects;

/**
 * EndUserPrincipal — authenticated end user (ADR 0014 / WP2-2). Not an {@code OperatorPrincipal}.
 * 已认证终端用户，不是操作员主体。
 * <p>
 * Tokens are account-scoped (like operator tokens). Tenant membership is an ACTIVE
 * {@code subject_identity} checked at issue time and again on tenant-scoped APIs.
 * 令牌按账号计（与操作员令牌类似）。租户成员关系是 ACTIVE {@code subject_identity}，签发时校验，租户作用域接口再校验。
 */
public final class EndUserPrincipal {

    private final String accountId;
    private final String subjectId;
    private final String loginName;
    private final boolean active;

    public EndUserPrincipal(String accountId, String subjectId, String loginName, boolean active) {
        this.accountId = require(accountId, "accountId");
        this.subjectId = require(subjectId, "subjectId");
        this.loginName = require(loginName, "loginName");
        this.active = active;
    }

    public String accountId() {
        return accountId;
    }

    public String subjectId() {
        return subjectId;
    }

    public String loginName() {
        return loginName;
    }

    public boolean active() {
        return active;
    }

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EndUserPrincipal that)) {
            return false;
        }
        return active == that.active
                && accountId.equals(that.accountId)
                && subjectId.equals(that.subjectId)
                && loginName.equals(that.loginName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, subjectId, loginName, active);
    }
}
