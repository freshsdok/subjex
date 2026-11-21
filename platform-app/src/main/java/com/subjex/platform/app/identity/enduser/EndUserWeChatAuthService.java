package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.idp.IdpExternalIdentity;
import com.subjex.platform.app.idp.WeChatIdpAdapter;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * EndUserWeChatAuthService — WeChat mini-program login with first-login auto-provision (ADR 0014 / WP2-4).
 * 微信小程序登录：首次登录可自动开户。
 * <p>
 * This is the opposite of the operator IdP policy (unlinked is denied, no auto-provision).
 * Never creates a tenant and never defaults to reserved {@code platform}.
 * 与操作员「未绑定即拒绝、不开户」相反。不建租户，不用保留租户 {@code platform}。
 */
public final class EndUserWeChatAuthService {

    private final EndUserAccountStore accounts;
    private final JdbcEndUserTokenStore tokens;
    private final EndUserWeChatAssertion weChat;
    private final JdbcEndUserLoginLockout lockout;
    private final JdbcTemplate jdbc;

    public EndUserWeChatAuthService(
            EndUserAccountStore accounts,
            JdbcEndUserTokenStore tokens,
            EndUserWeChatAssertion weChat,
            JdbcEndUserLoginLockout lockout,
            JdbcTemplate jdbc) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.weChat = Objects.requireNonNull(weChat, "weChat");
        this.lockout = Objects.requireNonNull(lockout, "lockout");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Login or first-time auto-provision in an explicit ACTIVE business tenant, then issue tokens.
     * 在显式 ACTIVE 业务租户中登录或首次自动开户，并签发令牌。
     */
    public JdbcEndUserTokenStore.IssuedTokens login(
            String jsCode, String tenantId, String userAgent, String clientIp) {
        if (!weChat.configured()) {
            throw new WeChatUnconfiguredException();
        }
        String tid = EndUserAccountStore.requireExplicitBusinessTenantId(tenantId);
        requireActiveBusinessTenant(tid);
        IdpExternalIdentity identity = weChat.assertMiniProgram(jsCode);
        String lockKey = JdbcEndUserLoginLockout.keyForWeChat(identity.issuer(), identity.subject());
        lockout.assertNotLockedKey(lockKey);
        Optional<EndUserRecord> existing = accounts.findByIdp(identity.issuer(), identity.subject());
        EndUserRecord record;
        if (existing.isEmpty()) {
            record = accounts.provision(tid, displayName(identity));
            accounts.bindIdp(
                    record.account().accountId(),
                    providerId(identity),
                    identity.issuer(),
                    identity.subject());
        } else {
            record = existing.get();
            if (accounts.findActiveIdentity(record.account().accountId(), tid).isEmpty()) {
                accounts.addIdentity(record.account().accountId(), tid);
            }
        }
        try {
            JdbcEndUserTokenStore.IssuedTokens issued =
                    tokens.issue(record.account().accountId(), tid, userAgent, clientIp);
            lockout.clearKey(lockKey);
            return issued;
        } catch (RuntimeException ex) {
            lockout.recordFailureKey(lockKey);
            throw ex;
        }
    }

    /**
     * Bind a WeChat identity to the authenticated end-user account after a valid jsCode.
     * 校验 jsCode 后把微信身份绑到已认证终端账号。
     */
    public void bind(EndUserPrincipal principal, String jsCode) {
        EndUserPrincipal actor = EndUserPhoneAuthService.requireEndUser(principal);
        if (!weChat.configured()) {
            throw new WeChatUnconfiguredException();
        }
        IdpExternalIdentity identity = weChat.assertMiniProgram(jsCode);
        accounts.bindIdp(actor.accountId(), providerId(identity), identity.issuer(), identity.subject());
    }

    /**
     * Unbind a WeChat identity from the authenticated account (jsCode and/or issuer+subject).
     * 从已认证账号解绑微信身份（可用 jsCode 和/或 issuer+subject）。
     */
    public void unbind(EndUserPrincipal principal, String jsCode, String issuer, String idpSubject) {
        EndUserPrincipal actor = EndUserPhoneAuthService.requireEndUser(principal);
        String iss;
        String sub;
        if (jsCode != null && !jsCode.isBlank()) {
            if (!weChat.configured()) {
                throw new WeChatUnconfiguredException();
            }
            IdpExternalIdentity identity = weChat.assertMiniProgram(jsCode);
            iss = identity.issuer();
            sub = identity.subject();
            if (issuer != null && !issuer.isBlank() && !iss.equals(issuer.trim())) {
                throw new IllegalArgumentException("idp issuer does not match jsCode");
            }
            if (idpSubject != null && !idpSubject.isBlank() && !sub.equals(idpSubject.trim())) {
                throw new IllegalArgumentException("idp subject does not match jsCode");
            }
        } else {
            iss = requireText(issuer, "issuer");
            sub = requireText(idpSubject, "idpSubject");
        }
        Optional<EndUserRecord> bound = accounts.findByIdp(iss, sub);
        if (bound.isEmpty() || !actor.accountId().equals(bound.get().account().accountId())) {
            throw new IllegalArgumentException("idp subject not bound to this account");
        }
        accounts.requireNotLastAuthenticator(actor.accountId());
        accounts.unbindIdp(actor.accountId(), iss, sub);
    }

    private static String providerId(IdpExternalIdentity identity) {
        String id = identity.providerId();
        return id == null || id.isBlank() ? WeChatIdpAdapter.ID : id.trim();
    }

    private static String displayName(IdpExternalIdentity identity) {
        return "wechat:" + identity.subject();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private void requireActiveBusinessTenant(String tenantId) {
        List<String> states = jdbc.query(
                "SELECT tenant_state FROM tenant WHERE tenant_id = ?",
                (row, n) -> row.getString("tenant_state"),
                tenantId);
        if (states.isEmpty()) {
            throw new TenantNotFoundException(tenantId);
        }
        String state = states.get(0) == null ? "" : states.get(0).trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(state)) {
            throw new TenantDisabledException(tenantId);
        }
    }
}
