package com.subjex.platform.contract.identity;

/**
 * Account — 账号：用来进入平台的登录主体。
 * <p>
 * It names the account. It does not store a secret. v1 operator access is separate from this type.
 * 它只为账号命名，不存放密钥。第一版的操作员入口与这个类型分开。
 */
public record Account(String accountId, String loginName, AccountState accountState) {
}
