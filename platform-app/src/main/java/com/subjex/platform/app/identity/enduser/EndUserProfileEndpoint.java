package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EndUserProfileEndpoint — WP7-1 GET/PATCH own display name under {@code /api/v1/end-user/profile}.
 * 终端资料：只读/更新本人展示名；不新增头像或邮箱列。
 */
@RestController
public class EndUserProfileEndpoint {

    /** JSON base for end-user profile — 终端资料 JSON 根路径. */
    public static final String PATH = JsonApi.BASE + "/end-user/profile";

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
            @RequestBody UpdateRequest body) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        String name = body == null ? null : body.subjectName();
        return ProfileDocument.from(
                shell.updateProfile(principal, resolveTenant(tenantHeader, tenantIdParam), name));
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

    /** Profile JSON — 资料文档. */
    public record ProfileDocument(String accountId, String subjectId, String subjectName) {
        static ProfileDocument from(EndUserRecord record) {
            return new ProfileDocument(
                    record.account().accountId(),
                    record.subject().subjectId(),
                    record.subject().subjectName());
        }
    }

    /** PATCH body: display name only — 仅展示名. */
    public record UpdateRequest(String subjectName) {}
}
