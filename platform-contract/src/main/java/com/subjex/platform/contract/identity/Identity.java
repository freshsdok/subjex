package com.subjex.platform.contract.identity;

/**
 * Identity — 身份：某个账号在某个租户里代表某个主体。
 * <p>
 * The three ids are the binding. Removing any one of them leaves an account, a subject, or a tenant, not an identity.
 * 三个标识合在一起才是这条绑定。少掉任何一个，就只剩账号、主体或租户，不再是身份。
 */
public record Identity(
        String identityId,
        String accountId,
        String subjectId,
        String tenantId,
        IdentityState identityState) {
}
