package com.subjex.platform.app.security;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.contract.audit.AuditOutcome;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OperatorAuthEndpoint — 登录 / 刷新 / 退出：口令只用于签发不透明 Bearer，之后用访问+刷新令牌。
 * <p>
 * Login validates username/password once and issues opaque access + rotating refresh tokens.
 * Refresh rotates; reuse of an old refresh revokes the family. Logout revokes the refresh family.
 * 登录核对一次口令后签发访问+刷新令牌。刷新会轮换；重用旧刷新则吊销整族。退出吊销刷新族。
 */
@RestController
public class OperatorAuthEndpoint {

    public static final String PATH = JsonApi.BASE + "/auth";

    private final JdbcOperatorDirectory directory;
    private final PasswordEncoder passwordEncoder;
    private final JdbcOperatorTokenStore tokenStore;
    private final OperatorActionAudit audit;

    public OperatorAuthEndpoint(
            JdbcOperatorDirectory directory,
            PasswordEncoder passwordEncoder,
            JdbcOperatorTokenStore tokenStore,
            OperatorActionAudit audit) {
        this.directory = directory;
        this.passwordEncoder = passwordEncoder;
        this.tokenStore = tokenStore;
        this.audit = audit;
    }

    @PostMapping(PATH + "/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        String loginName = body == null || body.loginName() == null ? "" : body.loginName().trim();
        String password = body == null ? null : body.password();
        if (loginName.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        OperatorPrincipal operator;
        try {
            operator = (OperatorPrincipal) directory.loadUserByUsername(loginName);
        } catch (UsernameNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!operator.isEnabled() || !passwordEncoder.matches(password, operator.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JdbcOperatorTokenStore.IssuedTokens issued =
                tokenStore.issue(operator, request.getHeader("User-Agent"), clientIp(request));
        audit.record(operator, "operator.auth.login", operator.getUsername(), AuditOutcome.ALLOWED);
        return ResponseEntity.ok(toResponse(issued));
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

    public record TokenResponse(String accessToken, String refreshToken, long expiresIn, String tokenType) {}
}
