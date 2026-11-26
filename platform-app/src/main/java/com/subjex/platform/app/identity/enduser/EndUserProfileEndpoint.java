package com.subjex.platform.app.identity.enduser;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.storage.IssuedDownloadLink;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EndUserProfileEndpoint — WP7-1 GET/PATCH own profile under {@code /api/v1/end-user/profile}.
 * 终端资料：展示名、授权轨头像，以及须验证后才生效的邮箱。
 * Email in a PATCH body is ignored; use the request-code and verify routes.
 * PATCH 正文中的邮箱字段会被忽略，须走申请验证码与验证接口。
 */
@RestController
public class EndUserProfileEndpoint {

    /** JSON base for end-user profile — 终端资料 JSON 根路径. */
    public static final String PATH = JsonApi.BASE + "/end-user/profile";

    public static final String AVATAR_PATH = PATH + "/avatar";

    public static final String EMAIL_CODE_PATH = PATH + "/email/request-code";

    public static final String EMAIL_VERIFY_PATH = PATH + "/email/verify";

    private final EndUserShellService shell;

    public EndUserProfileEndpoint(EndUserShellService shell) {
        this.shell = Objects.requireNonNull(shell, "shell");
    }

    @GetMapping(PATH)
    public ProfileDocument get(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        return ProfileDocument.from(shell.readProfile(principal, resolveTenant(tenantHeader, tenantIdParam)));
    }

    @PatchMapping(PATH)
    public ProfileDocument patch(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody(required = false) UpdateRequest body) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        String name = body == null ? null : body.subjectName();
        boolean setAvatar = body != null && body.avatarObjectIdSpecified();
        String avatarObjectId = setAvatar ? body.avatarObjectId() : null;
        return ProfileDocument.from(shell.updateProfile(
                principal, resolveTenant(tenantHeader, tenantIdParam), name, avatarObjectId, setAvatar));
    }

    @DeleteMapping(AVATAR_PATH)
    public ProfileDocument clearAvatar(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        return ProfileDocument.from(shell.clearAvatar(principal, resolveTenant(tenantHeader, tenantIdParam)));
    }

    @PostMapping(EMAIL_CODE_PATH)
    public ResponseEntity<?> requestEmailCode(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody(required = false) EmailRequest body) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        try {
            VerificationCodePort.IssuedCode issued = shell.requestEmailCode(
                    principal,
                    resolveTenant(tenantHeader, tenantIdParam),
                    body == null ? null : body.email());
            return ResponseEntity.ok(new RequestCodeResponse(issued.expiresInSeconds(), issued.purpose(), issued.devCode()));
        } catch (VerificationCodeSenderUnconfiguredException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("reason", VerificationCodeSenderUnconfiguredException.REASON));
        } catch (VerificationCodeSendFailedException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("reason", VerificationCodeSendFailedException.REASON));
        }
    }

    @PostMapping(EMAIL_VERIFY_PATH)
    public ResponseEntity<?> verifyEmail(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody(required = false) VerifyEmailRequest body) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        try {
            ProfileDocument document = ProfileDocument.from(shell.verifyEmail(
                    principal,
                    resolveTenant(tenantHeader, tenantIdParam),
                    body == null ? null : body.email(),
                    body == null ? null : body.code()));
            return ResponseEntity.ok(document);
        } catch (InvalidVerificationCodeException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("reason", "invalid-verification-code"));
        }
    }

    private static String resolveTenant(String header, String param) {
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (param != null && !param.isBlank()) {
            return param.trim();
        }
        return null;
    }

    /**
     * Profile JSON — 资料文档.
     * {@code email} is the verified address only. {@code pendingEmail} is not yet the profile email.
     * {@code email} 只反映已验证地址；{@code pendingEmail} 还不是资料邮箱。
     * Avatar is an object id plus a short-lived download, never a raw storage key.
     * 头像是对象标识加短时下载，不含原始存储键。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProfileDocument(
            String accountId,
            String subjectId,
            String subjectName,
            String avatarObjectId,
            IssuedDownloadLink avatarDownload,
            String email,
            Instant emailVerifiedAt,
            String pendingEmail,
            boolean emailVerified) {
        static ProfileDocument from(EndUserShellService.ProfileView view) {
            EndUserRecord record = view.record();
            EndUserProfile profile = view.profile();
            return new ProfileDocument(
                    record.account().accountId(),
                    record.subject().subjectId(),
                    record.subject().subjectName(),
                    profile.avatarObjectId(),
                    view.avatarDownload(),
                    profile.email(),
                    profile.emailVerifiedAt(),
                    profile.pendingEmail(),
                    profile.emailVerified());
        }
    }

    /**
     * PATCH body. {@code email} is accepted and ignored (must be verified separately).
     * PATCH 正文。{@code email} 即使出现也会被忽略，必须单独验证。
     * {@code avatarObjectId} is applied only when the field is present (null or blank clears).
     * 仅当出现 {@code avatarObjectId} 字段时才改头像（空值表示清除）。
     */
    public static final class UpdateRequest {
        private String subjectName;
        private String avatarObjectId;
        private boolean avatarObjectIdSpecified;
        private String email;

        public String subjectName() {
            return subjectName;
        }

        public void setSubjectName(String subjectName) {
            this.subjectName = subjectName;
        }

        public String avatarObjectId() {
            return avatarObjectId;
        }

        public void setAvatarObjectId(String avatarObjectId) {
            this.avatarObjectId = avatarObjectId;
            this.avatarObjectIdSpecified = true;
        }

        public boolean avatarObjectIdSpecified() {
            return avatarObjectIdSpecified;
        }

        public String email() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }

    /** Request a verification code for a pending email — 为待验证邮箱申请验证码. */
    public record EmailRequest(String email) {}

    public record VerifyEmailRequest(String email, String code) {}

    public record RequestCodeResponse(long expiresIn, String purpose, String devCode) {}
}
