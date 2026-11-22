package com.subjex.platform.app.resourceevent;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ResourceEventEndpoint — ingest share/open events; operator summary of what was actually recorded.
 * 接收分享/打开事件；操作员汇总只展示已经记下的内容。
 * <p>
 * POST is open to the end-user shell (actor filled only when signed in). GET needs {@code resource-event.read}.
 * POST 供终端壳调用（已登录才记下分享人）。GET 需要 {@code resource-event.read}。
 */
@RestController
public class ResourceEventEndpoint {

    public static final String PATH = JsonApi.BASE + "/resource-events";
    public static final String SUMMARY_PATH = PATH + "/summary";

    private final ResourceEventService events;

    public ResourceEventEndpoint(ResourceEventService events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    @PostMapping(PATH)
    public ResourceEventService.AcceptedEvent record(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestBody(required = false) ResourceEventService.IngestRequest body) {
        ResourceEventService.IngestRequest request =
                body == null
                        ? new ResourceEventService.IngestRequest(
                                null, null, null, null, null, null, null, null, null, null, null)
                        : body;
        return events.record(currentPrincipal(), tenantHeader, request);
    }

    @GetMapping(SUMMARY_PATH)
    public ResourceEventService.SummaryDocument summary(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to) {
        OperatorPrincipal operator = requireOperator();
        String tid = (tenantId == null || tenantId.isBlank()) ? tenantHeader : tenantId;
        return events.summarise(operator, tid, from, to);
    }

    private static Object currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getPrincipal();
    }

    private static OperatorPrincipal requireOperator() {
        Object principal = currentPrincipal();
        if (principal instanceof OperatorPrincipal operator) {
            return operator;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
