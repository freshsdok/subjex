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
 * LoadDashboardEndpoint — operator JSON snapshot of already-produced actuator/Micrometer meters.
 * 操作员 JSON：进程里已有的 actuator/Micrometer 负载快照。
 * <p>
 * Needs {@code admin.read}. Does not open the management port or talk to Grafana.
 * 需要 {@code admin.read}。不打开管理端口，也不连接 Grafana。
 */
@RestController
public class LoadDashboardEndpoint {

    public static final String PATH = JsonApi.BASE + "/load";

    private final LoadDashboardService load;

    public LoadDashboardEndpoint(LoadDashboardService load) {
        this.load = Objects.requireNonNull(load, "load");
    }

    @GetMapping(PATH)
    public LoadDashboardService.LoadSnapshot snapshot() {
        requireOperator();
        return load.snapshot();
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
