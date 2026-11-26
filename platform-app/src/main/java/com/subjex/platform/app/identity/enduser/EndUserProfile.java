package com.subjex.platform.app.identity.enduser;

import java.time.Instant;
import java.util.Objects;

/**
 * EndUserProfile — account-level avatar and email fields (not a new identity product).
 * 账号级头像与邮箱资料，不新增一套身份产品。
 *
 * @param accountId owning account — 所属账号
 * @param avatarObjectId authorized-track object id, never a storage key — 授权轨对象标识，不是存储键
 * @param avatarTenantId tenant where the avatar object was registered — 头像对象登记所在租户
 * @param email verified address only — 仅已验证邮箱
 * @param emailVerifiedAt when the current email was verified — 当前邮箱通过验证的时间
 * @param pendingEmail address waiting for a verification code — 待验证邮箱
 * @param pendingEmailRequestedAt when the pending address was last requested — 最近一次申请验证的时间
 */
public record EndUserProfile(
        String accountId,
        String avatarObjectId,
        String avatarTenantId,
        String email,
        Instant emailVerifiedAt,
        String pendingEmail,
        Instant pendingEmailRequestedAt) {

    public EndUserProfile {
        Objects.requireNonNull(accountId, "accountId");
    }

    /** Empty profile row for an account that has not set fields yet — 尚未填写资料字段的空行. */
    public static EndUserProfile empty(String accountId) {
        return new EndUserProfile(accountId, null, null, null, null, null, null);
    }

    /** True when a verified email is stored — 已存有通过验证的邮箱. */
    public boolean emailVerified() {
        return email != null && !email.isBlank();
    }
}
