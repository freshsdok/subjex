package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * EndUserDirectoryEndpoint — operator JSON list/lookup of existing end users in a granted tenant.
 * 操作员 JSON：在已授权租户内查阅已有终端用户。
 * <p>
 * Needs {@code end-user.read}. Read-only: no disable or unlink routes.
 * 需要 {@code end-user.read}。只读：无停用或解绑路由。
 */
@RestController
public class EndUserDirectoryEndpoint {

    public static final String PATH = JsonApi.BASE + "/end-users";

    private final EndUserDirectoryService directory;

    public EndUserDirectoryEndpoint(EndUserDirectoryService directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @GetMapping(PATH)
    public DirectoryDocument list(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "limit", required = false) Integer limit) {
        OperatorPrincipal operator = requireOperator();
        List<EndUserDirectoryService.DirectoryRow> rows = directory.list(operator, tenantId, query, limit);
        return new DirectoryDocument(tenantId == null ? null : tenantId.trim(), rows);
    }

    private static OperatorPrincipal requireOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof OperatorPrincipal operator) {
            return operator;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    public record DirectoryDocument(String tenantId, List<EndUserDirectoryService.DirectoryRow> users) {}
}
