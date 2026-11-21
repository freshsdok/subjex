package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * EndUserPhoneAuthService — phone register/login, bind/unbind, and lockout (ADR 0014 / WP2-3).
 * 手机号注册/登录、绑定/解绑与锁定。
 * <p>
 * Phone uniqueness is global on the account. Tenant must be an explicit existing ACTIVE business
 * tenant; this service never creates a tenant and never defaults to {@code platform}.
 * 手机号在账号上全局唯一。租户必须是显式且已存在的 ACTIVE 业务租户；本服务不建租户，不用 {@code platform}。
 */
public final class EndUserPhoneAuthService {

    private final EndUserAccountStore accounts;
    private final JdbcEndUserTokenStore tokens;
    private final VerificationCodePort codes;
    private final JdbcEndUserLoginLockout lockout;
    private final JdbcTemplate jdbc;

    public EndUserPhoneAuthService(
            EndUserAccountStore accounts,
            JdbcEndUserTokenStore tokens,
            VerificationCodePort codes,
            JdbcEndUserLoginLockout lockout,
            JdbcTemplate jdbc) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.codes = Objects.requireNonNull(codes, "codes");
        this.lockout = Objects.requireNonNull(lockout, "lockout");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public VerificationCodePort.IssuedCode requestCode(String phone, String purpose) {
        String number = requirePhone(phone);
        String usedFor = VerificationCodePort.requirePurpose(
                purpose == null || purpose.isBlank() ? VerificationCodePort.PURPOSE_LOGIN : purpose);
        if (VerificationCodePort.PURPOSE_LOGIN.equals(usedFor)) {
            lockout.assertNotLocked(number);
        }
        return codes.issue(number, usedFor);
    }

    /**
     * Register (first phone) or login, then issue an end-user token pair in the given tenant.
     * 首次注册或登录，并在给定租户签发终端令牌对。
     */
    public JdbcEndUserTokenStore.IssuedTokens loginOrRegister(
            String phone, String code, String tenantId, String userAgent, String clientIp) {
        String number = requirePhone(phone);
        lockout.assertNotLocked(number);
        String tid = EndUserAccountStore.requireExplicitBusinessTenantId(tenantId);
        requireActiveBusinessTenant(tid);
        if (!codes.verifyAndConsume(number, VerificationCodePort.PURPOSE_LOGIN, code)) {
            lockout.recordFailure(number);
            throw new InvalidVerificationCodeException();
        }
        Optional<EndUserRecord> existing = accounts.findByPhone(number);
        EndUserRecord record;
        if (existing.isEmpty()) {
            record = accounts.provision(tid, number);
            accounts.bindPhone(record.account().accountId(), number);
        } else {
            record = existing.get();
            if (accounts.findActiveIdentity(record.account().accountId(), tid).isEmpty()) {
                accounts.addIdentity(record.account().accountId(), tid);
            }
        }
        JdbcEndUserTokenStore.IssuedTokens issued =
                tokens.issue(record.account().accountId(), tid, userAgent, clientIp);
        lockout.clear(number);
        return issued;
    }

    /**
     * Bind a new phone to the authenticated end-user account after a valid bind code.
     * 校验绑定验证码后，将新手机号绑到已认证终端账号。
     */
    public void bind(EndUserPrincipal principal, String phone, String code) {
        EndUserPrincipal actor = requireEndUser(principal);
        String number = requirePhone(phone);
        if (!codes.verifyAndConsume(number, VerificationCodePort.PURPOSE_BIND, code)) {
            throw new InvalidVerificationCodeException();
        }
        accounts.bindPhone(actor.accountId(), number);
    }

    /**
     * Unbind a phone from the authenticated account after a valid unbind code.
     * The released number can no longer log in as this account and may bind elsewhere.
     * 校验解绑验证码后从本账号释放手机号。释放后不得再以该号登录本账号，且可绑定到其他账号。
     */
    public void unbind(EndUserPrincipal principal, String phone, String code) {
        EndUserPrincipal actor = requireEndUser(principal);
        String number = requirePhone(phone);
        Optional<EndUserRecord> bound = accounts.findByPhone(number);
        if (bound.isEmpty() || !actor.accountId().equals(bound.get().account().accountId())) {
            throw new IllegalArgumentException("phone not bound to this account");
        }
        accounts.requireNotLastAuthenticator(actor.accountId());
        if (!codes.verifyAndConsume(number, VerificationCodePort.PURPOSE_UNBIND, code)) {
            throw new InvalidVerificationCodeException();
        }
        accounts.unbindPhone(actor.accountId(), number);
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

    static String requirePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("phone is required");
        }
        String number = phone.trim();
        if (number.length() > 32) {
            throw new IllegalArgumentException("phone must be at most 32 characters");
        }
        return number;
    }

    /**
     * Resolve tenant from JSON body or {@code X-Tenant-Id}. Differing values fail closed.
     * 从 JSON 正文或请求头解析租户；两者不一致则失败关闭。
     */
    static String resolveTenantId(String bodyTenantId, String headerTenantId) {
        String body = bodyTenantId == null ? "" : bodyTenantId.trim();
        String header = headerTenantId == null ? "" : headerTenantId.trim();
        if (body.isEmpty() && header.isEmpty()) {
            throw new TenantMissingException();
        }
        if (!body.isEmpty() && !header.isEmpty() && !body.equals(header)) {
            throw new IllegalArgumentException("tenantId and X-Tenant-Id must match");
        }
        return body.isEmpty() ? header : body;
    }

    static EndUserPrincipal requireEndUser(EndUserPrincipal principal) {
        if (principal == null || !principal.active()) {
            throw new EndUserAuthenticationRequiredException();
        }
        return principal;
    }

    /**
     * Bind/unbind requires an active {@link EndUserPrincipal}, never an operator token.
     * 绑定/解绑必须是有效终端主体，不得使用操作员令牌。
     */
    public static final class EndUserAuthenticationRequiredException extends RuntimeException {

        public static final String REASON = "end-user-authentication-required";

        public EndUserAuthenticationRequiredException() {
            super(REASON);
        }
    }
}
