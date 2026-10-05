package com.subjex.platform.app.jdbc;

import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRoster;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcServiceRegistry — JDBC 服务名册：端点存在共享库的 {@code service_endpoint} 表里。
 * <p>
 * Registering the same service name again replaces its address. Every process on the same database,
 * and the same process after a restart, resolves the same endpoint. This is not a registry server.
 * 同一个服务名再次登记会替换地址。连同一个库的每个进程、以及重启后的同一进程，都解析到同一个端点。这不是注册中心服务器。
 */
public final class JdbcServiceRegistry implements ServiceRoster {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcServiceRegistry(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void register(ServiceEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        // Update first, insert when no row exists. A concurrent first insert loses on the primary key and retries the update.
        // 先更新，没有行时再插入。并发的首次插入在主键上落败后，改为再更新一次。
        int updated = update(endpoint);
        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO service_endpoint (service_name, host, port, registered_at) VALUES (?, ?, ?, ?)",
                        endpoint.serviceName(), endpoint.host(), endpoint.port(),
                        PlatformTables.timestamp(clock.instant()));
            } catch (DuplicateKeyException raced) {
                update(endpoint);
            }
        }
    }

    @Override
    public Optional<ServiceEndpoint> resolve(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(
                "SELECT service_name, host, port FROM service_endpoint WHERE service_name = ?",
                (row, rowNumber) -> new ServiceEndpoint(
                        row.getString("service_name"), row.getString("host"), row.getInt("port")),
                serviceName).stream().findFirst();
    }

    @Override
    public List<ServiceEndpoint> endpoints() {
        return jdbc.query(
                "SELECT service_name, host, port FROM service_endpoint ORDER BY service_name",
                (row, rowNumber) -> new ServiceEndpoint(
                        row.getString("service_name"), row.getString("host"), row.getInt("port")));
    }

    private int update(ServiceEndpoint endpoint) {
        return jdbc.update(
                "UPDATE service_endpoint SET host = ?, port = ?, registered_at = ? WHERE service_name = ?",
                endpoint.host(), endpoint.port(), PlatformTables.timestamp(clock.instant()), endpoint.serviceName());
    }
}
