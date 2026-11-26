package com.subjex.platform.app.identity.enduser;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * EndUserProfileStore — persist avatar object id and verified/pending email beside the identity triad.
 * 在身份三分表旁持久化头像对象标识与已验证/待验证邮箱，不改 subject 列。
 */
public interface EndUserProfileStore {

    /** Maximum stored email length — 邮箱长度上限. */
    int MAX_EMAIL_LENGTH = 256;

    /**
     * Conservative address shape after trim + lower-case (not a full RFC parser).
     * 修剪并小写后的保守格式（不是完整 RFC 解析）。
     */
    Pattern EMAIL_SHAPE = Pattern.compile("^[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}$");

    Optional<EndUserProfile> findByAccountId(String accountId);

    default EndUserProfile requireOrEmpty(String accountId) {
        String aid = requireAccountId(accountId);
        return findByAccountId(aid).orElseGet(() -> EndUserProfile.empty(aid));
    }

    /**
     * Set or clear the avatar reference. {@code objectId} and {@code tenantId} must both be
     * present, or both empty to clear.
     * 设置或清除头像引用；对象标识与租户须同时给出，或同时为空表示清除。
     */
    EndUserProfile setAvatar(String accountId, String objectId, String tenantId);

    /**
     * Store a pending address without touching a previously verified email.
     * 写入待验证地址，不改动已验证邮箱。
     */
    EndUserProfile setPendingEmail(String accountId, String email, Instant requestedAt);

    /**
     * Promote the pending address to the verified email when it still matches.
     * 待验证地址仍匹配时提升为已验证邮箱。
     */
    EndUserProfile confirmPendingEmail(String accountId, String email, Instant verifiedAt);

    static String requireAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required");
        }
        return accountId.trim();
    }

    /**
     * Trim, lower-case, bound, and shape-check an email. Does not mark it verified.
     * 修剪、小写、限长并检查形态；不会因此视为已验证。
     */
    static String requireEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }
        String value = email.trim().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("email must be at most " + MAX_EMAIL_LENGTH + " characters");
        }
        if (!EMAIL_SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("email is not a valid address");
        }
        return value;
    }
}
