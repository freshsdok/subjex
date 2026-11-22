package com.subjex.platform.app.admin;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * RateLimitCircuitObservationEndpoint — operator JSON of current rate-limit windows and delivery breaker.
 * 操作员 JSON：当前限额时间窗与投递熔断开合。
 * <p>
 * Needs {@code admin.read}. Hung on the existing limiter and breaker beans, not actuator meters or Grafana.
 * 需要 {@code admin.read}。挂在已有限额与熔断实现上，不读 actuator 指标，也不连接 Grafana。
 */
@RestController
public class RateLimitCircuitObservationEndpoint {

    public static final String PATH = JsonApi.BASE + "/rate-limits";

    private final RateLimitCircuitObservationService observation;

    public RateLimitCircuitObservationEndpoint(RateLimitCircuitObservationService observation) {
        this.observation = Objects.requireNonNull(observation, "observation");
    }

    @GetMapping(PATH)
    public RateLimitCircuitObservationService.ObservationDocument snapshot() {
        requireOperator();
        return observation.snapshot();
    }

    private static OperatorPrincipal requireOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof OperatorPrincipal operator) {
            return operator;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
