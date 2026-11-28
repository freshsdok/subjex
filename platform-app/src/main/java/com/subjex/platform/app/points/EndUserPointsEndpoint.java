package com.subjex.platform.app.points;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.EndUserPrincipals;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.points.PointsAccountDocument;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EndUserPointsEndpoint — own balance and history under {@code /api/v1/end-user/points}. No adjust.
 * 终端本人余额与历史；不能调整。
 */
@RestController
public class EndUserPointsEndpoint {

    /** JSON base for the end-user points page — 终端「我的积分」JSON 根路径. */
    public static final String PATH = JsonApi.BASE + "/end-user/points";

    private final EndUserPointsService points;

    public EndUserPointsEndpoint(EndUserPointsService points) {
        this.points = Objects.requireNonNull(points, "points");
    }

    @GetMapping(PATH)
    public PointsAccountDocument get(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "limit", required = false) Integer limit) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        return points.getOwn(principal, resolveTenant(tenantHeader, tenantIdParam), limit);
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
