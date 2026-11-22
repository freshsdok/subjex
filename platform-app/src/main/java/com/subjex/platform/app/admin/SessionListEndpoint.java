package com.subjex.platform.app.admin;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * SessionListEndpoint — operator JSON for current refresh-token logins.
 * 操作员查看当前刷新令牌登录的 JSON。
 * <p>
 * List needs {@code admin.read}. Revoke needs {@code operator.manage} (HTTP security).
 * End-user rows are tenant-scoped and require a tenant grant.
 * 列表要 {@code admin.read}。吊销要 {@code operator.manage}（由安全配置强制）。
 * 终端登录按租户过滤，并核对该租户授权。
 */
@RestController
public class SessionListEndpoint {

    public static final String PATH = JsonApi.BASE + "/sessions";

    private final SessionListService sessions;

    public SessionListEndpoint(SessionListService sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @GetMapping(PATH + "/operators")
    public SessionListDocument operators(@RequestParam(value = "limit", required = false) Integer limit) {
        requireOperator();
        return toDocument(SessionListService.REALM_OPERATOR, null, sessions.listOperators(limit));
    }

    @PostMapping(PATH + "/operators/{familyId}/revoke")
    public RevokeDocument revokeOperator(@PathVariable("familyId") String familyId) {
        OperatorPrincipal operator = requireOperator();
        if (!sessions.revokeOperatorFamily(operator, familyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session missing");
        }
        return new RevokeDocument(familyId, true);
    }

    @GetMapping(PATH + "/end-users")
    public SessionListDocument endUsers(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        OperatorPrincipal operator = requireOperator();
        return toDocument(SessionListService.REALM_END_USER, tenantId, sessions.listEndUsers(operator, tenantId, limit));
    }

    @PostMapping(PATH + "/end-users/{familyId}/revoke")
    public RevokeDocument revokeEndUser(
            @PathVariable("familyId") String familyId,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        OperatorPrincipal operator = requireOperator();
        if (!sessions.revokeEndUserFamily(operator, tenantId, familyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session missing");
        }
        return new RevokeDocument(familyId, true);
    }

    private static OperatorPrincipal requireOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof OperatorPrincipal operator) {
            return operator;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    private static SessionListDocument toDocument(String realm, String tenantId, List<SessionListService.SessionRow> rows) {
        List<SessionDocument> sessions = rows.stream()
                .map(row -> new SessionDocument(
                        row.familyId(),
                        row.realm(),
                        row.subjectId(),
                        row.displayName(),
                        row.tenantId(),
                        row.userAgent(),
                        row.clientIp(),
                        row.expiresAt()))
                .toList();
        return new SessionListDocument(sessions, realm, tenantId);
    }

    public record SessionListDocument(List<SessionDocument> sessions, String realm, String tenantId) {}

    public record SessionDocument(
            String familyId,
            String realm,
            String subjectId,
            String displayName,
            String tenantId,
            String userAgent,
            String clientIp,
            Instant expiresAt) {}

    public record RevokeDocument(String familyId, boolean revoked) {}
}
