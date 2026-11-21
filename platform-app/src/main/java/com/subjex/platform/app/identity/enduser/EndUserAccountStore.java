package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.contract.identity.Identity;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.util.Optional;

/**
 * EndUserAccountStore — WP2-1 end-user account + bindings (no token issue, no login).
 * 终端用户账号与绑定（本切片不签发令牌、不实现登录）。
 * <p>
 * Reuses {@code account} / {@code subject} / {@code subject_identity}. Does not write operator tables.
 * Home tenant must be explicit and must not be reserved {@link #RESERVED_TENANT}.
 * 复用身份三分表，不写操作员表。归属租户必须显式给出，且不得为保留租户 {@link #RESERVED_TENANT}。
 */
public interface EndUserAccountStore {

    /**
     * Reserved operator tenant — never an end-user home (same id as {@code JdbcOperatorDirectory.OPERATOR_TENANT}).
     * 保留操作员租户，不得作为终端归属。
     */
    String RESERVED_TENANT = "platform";

    /**
     * Create account + PERSON subject + ACTIVE identity in an existing ACTIVE business tenant.
     * 在已存在且 ACTIVE 的业务租户中创建账号、PERSON 主体与 ACTIVE 身份。
     */
    EndUserRecord provision(String tenantId, String displayName);

    /**
     * Add an ACTIVE identity for an existing end-user account in another explicit tenant.
     * 为已有终端账号在另一个显式租户中增加 ACTIVE 身份。
     */
    Identity addIdentity(String accountId, String tenantId);

    /** Bind a globally unique phone to the account — 为账号绑定全局唯一手机号。 */
    void bindPhone(String accountId, String phone);

    /**
     * Release the phone from this account so it may bind to another. The account remains.
     * 从本账号释放该手机号，之后可绑定到其他账号。账号本身保留。
     */
    void unbindPhone(String accountId, String phone);

    /**
     * Bind an IdP subject (issuer + idp_subject, e.g. {@code wechat:mini} + unionId/openId).
     * 绑定 IdP 主体（issuer + idp_subject，如 {@code wechat:mini} + unionId/openId）。
     */
    void bindIdp(String accountId, String providerId, String issuer, String idpSubject);

    /**
     * Release an IdP link from this account so the subject may bind to another. The account remains.
     * 从本账号释放该 IdP 绑定，之后可绑定到其他账号。账号本身保留。
     */
    void unbindIdp(String accountId, String issuer, String idpSubject);

    /**
     * Phone bindings plus IdP links for last-authenticator checks.
     * 手机号与 IdP 绑定数，供“最后认证器”检查。
     */
    int authenticatorCount(String accountId);

    /**
     * Refuse unbind when this is the account's only remaining sign-in method.
     * 若这是账号仅剩的登录方式，则拒绝解绑。
     */
    default void requireNotLastAuthenticator(String accountId) {
        if (authenticatorCount(accountId) <= 1) {
            throw new LastAuthenticatorException();
        }
    }

    Optional<EndUserRecord> findByAccountId(String accountId);

    /**
     * Maximum stored {@code subject.subject_name} length (VARCHAR(256)).
     * 展示名长度上限，与 {@code subject.subject_name} 列宽一致。
     */
    int MAX_DISPLAY_NAME_LENGTH = 256;

    /**
     * Replace the account's subject display name ({@code subject.subject_name} only).
     * 只更新该账号主体的展示名，不新增列。
     */
    EndUserRecord updateDisplayName(String accountId, String displayName);

    /**
     * Tenant must exist, be ACTIVE, and not be the reserved operator tenant.
     * 租户必须已存在、为 ACTIVE，且不是保留操作员租户。
     */
    void requireActiveBusinessTenant(String tenantId);

    /**
     * Fail-closed membership: explicit business tenant + ACTIVE identity, else denied.
     * 失败关闭的成员关系：显式业务租户且有 ACTIVE 身份，否则拒绝。
     */
    default Identity requireActiveMembership(String accountId, String tenantId) {
        String tid = requireExplicitBusinessTenantId(tenantId);
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required");
        }
        requireActiveBusinessTenant(tid);
        return findActiveIdentity(accountId.trim(), tid)
                .orElseThrow(EndUserTenantAccessDeniedException::new);
    }

    /**
     * Trim and bound a display name (1–{@link #MAX_DISPLAY_NAME_LENGTH} chars).
     * 修剪并限制展示名长度。
     */
    static String requireDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display name is required");
        }
        String name = displayName.trim();
        if (name.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "display name must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        return name;
    }

    Optional<EndUserRecord> findByPhone(String phone);

    Optional<EndUserRecord> findByIdp(String issuer, String idpSubject);

    /**
     * ACTIVE identity in the given tenant, or empty (fail-closed for callers).
     * 给定租户下的 ACTIVE 身份；没有则空（调用方失败关闭）。
     */
    Optional<Identity> findActiveIdentity(String accountId, String tenantId);

    /**
     * Reject a missing or reserved tenant id before any lookup.
     * 在查找之前拒绝缺失或保留租户标识。
     */
    static String requireExplicitBusinessTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String id = tenantId.trim();
        if (RESERVED_TENANT.equals(id)) {
            throw new IllegalArgumentException(
                    "reserved tenant " + RESERVED_TENANT + " is not an end-user home");
        }
        return id;
    }
}
