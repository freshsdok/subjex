package com.subjex.platform.app.points;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.points.PointsAccountDocument;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OperatorPointsEndpoint — look up and adjust a subject ledger under {@code /api/v1/points}.
 * 操作员在 {@code /api/v1/points} 查阅并调整主体台账。不是忠诚计划或兑换目录。
 */
@RestController
public class OperatorPointsEndpoint {

    /** JSON base for operator points queries — 操作员积分查询 JSON 根路径. */
    public static final String PATH = JsonApi.BASE + "/points";

    private final OperatorPointsService points;

    public OperatorPointsEndpoint(OperatorPointsService points) {
        this.points = Objects.requireNonNull(points, "points");
    }

    @GetMapping(PATH)
    public PointsAccountDocument get(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "subjectId", required = false) String subjectId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return points.get(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), subjectId, limit);
    }

    @PostMapping(PATH + "/adjustments")
    public PointsAccountDocument adjust(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = "X-Idempotency-Token", required = false) String idempotencyHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody(required = false) AdjustPointsRequest body) {
        AdjustPointsRequest request =
                body == null ? new AdjustPointsRequest(null, null, null, null, null, null, null) : body;
        AdjustPointsRequest.Reference reference = request.reference();
        return points.adjust(
                requireOperator(),
                resolveTenant(tenantHeader, tenantIdParam),
                request.subjectId(),
                request.direction(),
                request.amount(),
                request.reason(),
                request.memo(),
                reference == null ? null : reference.type(),
                reference == null ? null : reference.id(),
                firstNonBlank(idempotencyHeader, request.idempotencyToken()));
    }

    private static OperatorPrincipal requireOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof OperatorPrincipal operator) {
            return operator;
        }
        throw new org.springframework.security.access.AccessDeniedException("operator-authentication-required");
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
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
