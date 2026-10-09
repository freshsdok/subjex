package com.subjex.platform.app.security;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.contract.audit.AuditOutcome;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OperatorAuthEndpoint — 登录 / 刷新 / 退出 / MFA 校验：口令只用于签发不透明 Bearer（或 MFA 挑战）。
 * <p>
 * Login validates username/password once. If TOTP is enrolled, returns a short-lived {@code mfaToken}
 * instead of access/refresh; {@code POST .../mfa/verify} completes the flow. Refresh rotates; reuse of
 * an old refresh revokes the family. Logout revokes the refresh family.
 * Failed password (or MFA) attempts feed {@link JdbcOperatorLoginLockout}; active lockout returns 429.
 * 登录核对一次口令。若已登记 TOTP 则先发短时 mfaToken；校验后再签发访问/刷新。刷新轮换；重用旧刷新吊销整族。
 * 口令（及 MFA）失败计入锁定；仍在锁定窗口内返回 429。
 */
@RestController
public class OperatorAuthEndpoint {

    public static final String PATH = JsonApi.BASE + "/auth";

    private final JdbcOperatorDirectory directory;
    private final PasswordEncoder passwordEncoder;
    private final JdbcOperatorTokenStore tokenStore;
    private final JdbcOperatorMfaStore mfaStore;
    private final JdbcOperatorLoginLockout loginLockout;
    private final OperatorActionAudit audit;

    public OperatorAuthEndpoint(
            JdbcOperatorDirectory directory,
            PasswordEncoder passwordEncoder,
            JdbcOperatorTokenStore tokenStore,
            JdbcOperatorMfaStore mfaStore,
            JdbcOperatorLoginLockout loginLockout,
            OperatorActionAudit audit) {
        this.directory = directory;
        this.passwordEncoder = passwordEncoder;
        this.tokenStore = tokenStore;
        this.mfaStore = mfaStore;
        this.loginLockout = loginLockout;
        this.audit = audit;
    }

    @PostMapping(PATH + "/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        String loginName = body == null || body.loginName() == null ? "" : body.loginName().trim();
        String password = body == null ? null : body.password();
        if (loginName.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            loginLockout.assertNotLocked(loginName);
        } catch (LoginLockoutException ex) {
            auditLockoutRefusal(loginName);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("reason", "login-lockout"));
        }
        OperatorPrincipal operator;
        try {
            operator = (OperatorPrincipal) directory.loadUserByUsername(loginName);
        } catch (UsernameNotFoundException ex) {
            loginLockout.recordFailure(loginName);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!operator.isEnabled() || !passwordEncoder.matches(password, operator.getPassword())) {
            loginLockout.recordFailure(loginName);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        loginLockout.clear(loginName);
        if (mfaStore.isEnrolled(operator.subjectId())) {
            JdbcOperatorMfaStore.IssuedChallenge challenge = mfaStore.issueChallenge(operator);
            audit.record(operator, "operator.auth.login.mfa-challenge", operator.getUsername(), AuditOutcome.ALLOWED);
            return ResponseEntity.ok(new MfaChallengeResponse(
                    true, challenge.mfaToken(), challenge.expiresInSeconds(), "MfaChallenge"));
        }
        if (mfaStore.enrollmentRequired(operator)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("reason", "mfa-enrollment-required"));
        }
        JdbcOperatorTokenStore.IssuedTokens issued =
                tokenStore.issue(operator, request.getHeader("User-Agent"), clientIp(request));
        audit.record(operator, "operator.auth.login", operator.getUsername(), AuditOutcome.ALLOWED);
        return ResponseEntity.ok(toResponse(issued));
    }

    @PostMapping(PATH + "/mfa/verify")
    public ResponseEntity<?> verifyMfa(@RequestBody MfaVerifyRequest body, HttpServletRequest request) {
        String mfaToken = body == null ? null : body.mfaToken();
        String code = body == null ? null : body.code();
        try {
            OperatorPrincipal operator = mfaStore.verifyChallenge(mfaToken, code);
            loginLockout.clear(operator.getUsername());
            JdbcOperatorTokenStore.IssuedTokens issued =
                    tokenStore.issue(operator, request.getHeader("User-Agent"), clientIp(request));
            audit.record(operator, "operator.auth.login", operator.getUsername(), AuditOutcome.ALLOWED);
            return ResponseEntity.ok(toResponse(issued));
        } catch (InvalidOperatorTokenException ex) {
            var loginOpt = mfaStore.loginNameForChallenge(mfaToken);
            if (loginOpt.isPresent()) {
                String login = loginOpt.get();
                try {
                    loginLockout.assertNotLocked(login);
                } catch (LoginLockoutException lockEx) {
                    auditLockoutRefusal(login);
                    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                            .body(Map.of("reason", "login-lockout"));
                }
                loginLockout.recordFailure(login);
                try {
                    OperatorPrincipal actor = (OperatorPrincipal) directory.loadUserByUsername(login);
                    audit.record(actor, "operator.mfa.verify", login, AuditOutcome.REFUSED);
                } catch (UsernameNotFoundException ignored) {
                    // Challenge may already be gone; skip audit.
                }
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("reason", "invalid-mfa"));
        }
    }

    @PostMapping(PATH + "/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody RefreshRequest body, HttpServletRequest request) {
        String refreshToken = body == null ? null : body.refreshToken();
        try {
            JdbcOperatorTokenStore.IssuedTokens issued =
                    tokenStore.refresh(refreshToken, request.getHeader("User-Agent"), clientIp(request));
            return ResponseEntity.ok(toResponse(issued));
        } catch (InvalidOperatorTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping(PATH + "/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest body) {
        if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
            tokenStore.revokeByRefreshToken(body.refreshToken());
        }
        return ResponseEntity.noContent().build();
    }

    private void auditLockoutRefusal(String loginName) {
        try {
            OperatorPrincipal actor = (OperatorPrincipal) directory.loadUserByUsername(loginName);
            audit.record(actor, "operator.auth.login", loginName + ":lockout", AuditOutcome.REFUSED);
        } catch (UsernameNotFoundException ignored) {
            // Unknown login keys can still lock; no actor to audit.
            // 未知登录名也可锁定；无操作员主体可写审计。
        }
    }

    private static TokenResponse toResponse(JdbcOperatorTokenStore.IssuedTokens issued) {
        return new TokenResponse(
                issued.accessToken(),
                issued.refreshToken(),
                issued.expiresInSeconds(),
                "Bearer");
    }

    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    public record LoginRequest(String loginName, String password) {}

    public record RefreshRequest(String refreshToken) {}

    public record MfaVerifyRequest(String mfaToken, String code) {}

    public record TokenResponse(String accessToken, String refreshToken, long expiresIn, String tokenType) {}

    public record MfaChallengeResponse(
            boolean mfaRequired, String mfaToken, long expiresIn, String tokenType) {}
}
