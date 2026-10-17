package com.subjex.platform.contract.inbox;

/**
 * InboxRecipientRealm — 收件人领域：操作员控制台 vs 终端用户（同一表，禁止跨域列表）。
 * <p>
 * Console MVP lists {@link #OPERATOR} only; {@link #END_USER} waits for uni-app auth (IM-6).
 * 控制台首波只列 {@link #OPERATOR}；{@link #END_USER} 等 uni-app 登录（IM-6）。
 */
public enum InboxRecipientRealm {

    /** Operator console audience — 操作员控制台受众. */
    OPERATOR,
    /** End-user (uni-app) audience — 终端用户（uni-app）受众. */
    END_USER
}
