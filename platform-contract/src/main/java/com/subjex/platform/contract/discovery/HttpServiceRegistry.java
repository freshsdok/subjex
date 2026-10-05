package com.subjex.platform.contract.discovery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * HttpServiceRegistry — HTTP 登记簿：把 register 和 resolve 发给 platform-app，不另起注册中心。
 * <p>
 * The registry address is the static platform-app host and port. When that call cannot connect,
 * {@link #resolve} is empty so static configuration can answer, and {@link #register} does not stop the process.
 * A refused operator (401 or 403) is a configuration mistake and is not treated as an empty registry.
 * 登记地址就是静态配置里的 platform-app 主机和端口。连不上时 {@link #resolve} 为空，好让静态配置回答，
 * {@link #register} 也不叫停进程。操作员被拒绝（401 或 403）是配置错误，不当成空登记簿。
 */
public final class HttpServiceRegistry implements ServiceRegistry {

    /** List and registration path on platform-app — platform-app 上的名单与登记路径。 */
    public static final String SERVICES_PATH = "/registry/services";

    private final URI services;
    private final String authorization;
    private final Duration timeout;
    private final HttpClient client;

    public HttpServiceRegistry(URI platformRoot, String username, String password, Duration timeout) {
        Objects.requireNonNull(platformRoot, "platformRoot");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("registry operator name is missing");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("registry operator password is missing");
        }
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("registry timeout must be positive");
        }
        this.services = URI.create(platformRoot.toString().replaceAll("/+$", "") + SERVICES_PATH);
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public void register(ServiceEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        HttpRequest request = HttpRequest.newBuilder(services)
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ServiceEndpointJson.registration(endpoint)))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 204 || code == 200) {
                return;
            }
            if (code == 401 || code == 403) {
                throw new IllegalStateException("registry refused the operator");
            }
            throw new IllegalStateException("registry rejected the registration");
        } catch (IOException ex) {
            // Unreachable registry: the process still starts, and resolve falls back to static host and port.
            // 登记簿连不上：进程仍启动，解析回退到静态主机和端口。
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Optional<ServiceEndpoint> resolve(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return Optional.empty();
        }
        HttpRequest request = HttpRequest.newBuilder(services)
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 401 || code == 403) {
                throw new IllegalStateException("registry refused the operator");
            }
            if (code != 200) {
                return Optional.empty();
            }
            return ServiceEndpointJson.parseList(response.body()).stream()
                    .filter(endpoint -> endpoint.serviceName().equals(serviceName))
                    .findFirst();
        } catch (IOException | IllegalArgumentException ex) {
            // Unreadable or unreachable: not a registry this process can use.
            // 读不懂或连不上：这个进程用不了这份登记。
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
