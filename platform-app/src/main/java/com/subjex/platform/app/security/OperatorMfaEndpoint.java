package com.subjex.platform.app.security;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OperatorMfaEndpoint — TOTP 登记 / 关闭 / 状态；登录挑战校验在 {@link OperatorAuthEndpoint}。
 * <p>
 * Start → confirm returns one-time recovery codes. Disable needs current password and a TOTP or recovery code.
 * 开始→确认返回一次性恢复码。关闭需当前口令与 TOTP 或恢复码。
 */
@RestController
public class OperatorMfaEndpoint {

    public static final String PATH = JsonApi.BASE + "/auth/mfa";

    private final JdbcOperatorMfaStore mfaStore;
    private final PasswordEncoder passwordEncoder;
    private final JdbcOperatorTokenStore tokenStore;
    private final OperatorActionAudit audit;

    public OperatorMfaEndpoint(
            JdbcOperatorMfaStore mfaStore,
            PasswordEncoder passwordEncoder,
            JdbcOperatorTokenStore tokenStore,
            OperatorActionAudit audit) {
        this.mfaStore = mfaStore;
        this.passwordEncoder = passwordEncoder;
        this.tokenStore = tokenStore;
        this.audit = audit;
    }

    @GetMapping(PATH)
    public MfaStatusDocument status(@AuthenticationPrincipal OperatorPrincipal operator) {
        return new MfaStatusDocument(
                mfaStore.isEnrolled(operator.subjectId()),
                mfaStore.enrollmentRequired(operator));
    }

    @PostMapping(PATH + "/totp/start")
    public ResponseEntity<?> start(@AuthenticationPrincipal OperatorPrincipal operator) {
        try {
            JdbcOperatorMfaStore.EnrollmentStart started = mfaStore.startEnrollment(operator);
            return ResponseEntity.ok(new TotpStartDocument(started.secret(), started.otpauthUri()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("reason", "already-enrolled"));
        }
    }

    @PostMapping(PATH + "/totp/confirm")
    public ResponseEntity<?> confirm(
            @AuthenticationPrincipal OperatorPrincipal operator, @RequestBody TotpCodeRequest body) {
        String code = body == null ? null : body.code();
        try {
            List<String> recoveryCodes = mfaStore.confirmEnrollment(operator, code);
            audit.record(operator, "operator.mfa.enroll", operator.getUsername(), AuditOutcome.ALLOWED);
            return ResponseEntity.ok(new TotpConfirmDocument(recoveryCodes));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("reason", "no-pending-enrollment"));
        } catch (InvalidOperatorTokenException ex) {
            audit.record(operator, "operator.mfa.verify", operator.getUsername(), AuditOutcome.REFUSED);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("reason", "invalid-code"));
        }
    }

    @PostMapping(PATH + "/totp/disable")
    public ResponseEntity<?> disable(
            @AuthenticationPrincipal OperatorPrincipal operator, @RequestBody DisableMfaRequest body) {
        String password = body == null ? null : body.password();
        String code = body == null ? null : body.code();
        if (password == null
                || password.isEmpty()
                || !passwordEncoder.matches(password, operator.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("reason", "wrong-password"));
        }
        try {
            mfaStore.disable(operator, code);
            tokenStore.revokeAllForSubject(operator.subjectId());
            audit.record(operator, "operator.mfa.disable", operator.getUsername(), AuditOutcome.ALLOWED);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("reason", "not-enrolled"));
        } catch (InvalidOperatorTokenException ex) {
            audit.record(operator, "operator.mfa.verify", operator.getUsername(), AuditOutcome.REFUSED);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("reason", "invalid-code"));
        }
    }

    public record MfaStatusDocument(boolean enrolled, boolean enrollmentRequired) {}

    public record TotpStartDocument(String secret, String otpauthUri) {}

    public record TotpConfirmDocument(List<String> recoveryCodes) {}

    public record TotpCodeRequest(String code) {}

    public record DisableMfaRequest(String password, String code) {}
}
