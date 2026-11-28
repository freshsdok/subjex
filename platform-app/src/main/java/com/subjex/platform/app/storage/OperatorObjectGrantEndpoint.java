package com.subjex.platform.app.storage;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OperatorObjectGrantEndpoint — operator list / grant / revoke under {@code /api/v1/objects}.
 * 操作员对象授权：列出已登记对象、列出/授予/撤销授权。不是公开分享产品。
 */
@RestController
public class OperatorObjectGrantEndpoint {

    /** Operator registered-object collection — 操作员已登记对象集合. */
    public static final String PATH = JsonApi.BASE + "/objects";

    public static final String OBJECT_PATH = PATH + "/{objectId}";

    public static final String GRANTS_PATH = OBJECT_PATH + "/grants";

    public static final String GRANT_PATH = GRANTS_PATH + "/{grantId}";

    private final ObjectGrantManagementService grants;

    public OperatorObjectGrantEndpoint(ObjectGrantManagementService grants) {
        this.grants = Objects.requireNonNull(grants, "grants");
    }

    @GetMapping(PATH)
    public ObjectListDocument list(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        int lim = limit == null ? 50 : limit;
        int off = offset == null ? 0 : offset;
        List<ObjectGrantManagementService.ObjectListItem> items =
                grants.listRegistered(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), lim, off);
        return new ObjectListDocument(items, lim, off);
    }

    @GetMapping(GRANTS_PATH)
    public ObjectGrantManagementService.GrantList listGrants(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("objectId") String objectId) {
        return grants.listForOperator(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), objectId);
    }

    @PostMapping(path = GRANTS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ObjectGrantManagementService.GrantItem grant(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("objectId") String objectId,
            @RequestBody GrantRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        return grants.grantForOperator(
                requireOperator(),
                resolveTenant(tenantHeader, tenantIdParam),
                objectId,
                body.granteeKind(),
                body.granteeId(),
                body.expiresAt());
    }

    @DeleteMapping(GRANT_PATH)
    public ResponseEntity<Void> revoke(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("objectId") String objectId,
            @PathVariable("grantId") String grantId) {
        grants.revokeForOperator(
                requireOperator(), resolveTenant(tenantHeader, tenantIdParam), objectId, grantId);
        return ResponseEntity.noContent().build();
    }

    private static OperatorPrincipal requireOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof OperatorPrincipal operator) {
            return operator;
        }
        throw new AccessDeniedException("operator-authentication-required");
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

    /** Tenant object page — 租户对象分页. */
    public record ObjectListDocument(
            List<ObjectGrantManagementService.ObjectListItem> objects, int limit, int offset) {}

    /** Grant body — 授予请求体. */
    public record GrantRequest(String granteeKind, String granteeId, Instant expiresAt) {}
}
