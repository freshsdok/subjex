package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.storage.ObjectGrantManagementService;
import com.subjex.platform.app.storage.OperatorObjectGrantEndpoint;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EndUserObjectGrantEndpoint — owner-only grant management under {@code /api/v1/end-user/objects}.
 * 终端对象授权：仅对象所有者可列出、授予或撤销；不是公开分享或营销产品。
 */
@RestController
public class EndUserObjectGrantEndpoint {

    public static final String GRANTS_PATH = JsonApi.BASE + "/end-user/objects/{objectId}/grants";

    public static final String GRANT_PATH = GRANTS_PATH + "/{grantId}";

    private final ObjectGrantManagementService grants;

    public EndUserObjectGrantEndpoint(ObjectGrantManagementService grants) {
        this.grants = Objects.requireNonNull(grants, "grants");
    }

    @GetMapping(GRANTS_PATH)
    public ObjectGrantManagementService.GrantList listGrants(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("objectId") String objectId) {
        return grants.listForEndUser(
                EndUserPrincipals.requireEndUserPrincipal(), resolveTenant(tenantHeader, tenantIdParam), objectId);
    }

    @PostMapping(path = GRANTS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ObjectGrantManagementService.GrantItem grant(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("objectId") String objectId,
            @RequestBody OperatorObjectGrantEndpoint.GrantRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        return grants.grantForEndUser(
                EndUserPrincipals.requireEndUserPrincipal(),
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
        grants.revokeForEndUser(
                EndUserPrincipals.requireEndUserPrincipal(),
                resolveTenant(tenantHeader, tenantIdParam),
                objectId,
                grantId);
        return ResponseEntity.noContent().build();
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
}
