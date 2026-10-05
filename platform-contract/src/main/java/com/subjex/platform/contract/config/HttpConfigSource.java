package com.subjex.platform.contract.config;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * HttpConfigSource — HTTP 配置来源：向 platform-app 读取一个键的生效值，或压过一个键。
 * <p>
 * When the call cannot connect, {@link #lookup} is empty so local application config can answer.
 * A refused operator (401 or 403) is a configuration mistake and is not treated as a missing key.
 * 连不上时 {@link #lookup} 为空，好让本地应用配置回答。
 * 操作员被拒绝（401 或 403）是配置错误，不当成键不存在。
 */
public final class HttpConfigSource implements ConfigSource {

    /** One named key, under this path — 一个具名键，挂在这条路径下。 */
    public static final String ENTRIES_PATH = "/config/entries";

    private final URI entries;
    private final String authorization;
    private final Duration timeout;
    private final HttpClient client;

    public HttpConfigSource(URI platformRoot, String username, String password, Duration timeout) {
        Objects.requireNonNull(platformRoot, "platformRoot");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("config operator name is missing");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("config operator password is missing");
        }
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("config timeout must be positive");
        }
        this.entries = URI.create(platformRoot.toString().replaceAll("/+$", "") + ENTRIES_PATH);
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Optional<String> lookup(String key) {
        Optional<ConfigEntry> entry = read(key);
        return entry.map(ConfigEntry::value);
    }

    /**
     * Effective entry for one named key — 一个具名键的生效条目。
     */
    public Optional<ConfigEntry> read(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        URI target = URI.create(entries + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 401 || code == 403) {
                throw new IllegalStateException("config refused the operator");
            }
            if (code == 404) {
                return Optional.empty();
            }
            if (code != 200) {
                return Optional.empty();
            }
            return Optional.of(ConfigEntryJson.parse(response.body()));
        } catch (IOException | IllegalArgumentException ex) {
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Store one named override on platform-app — 在 platform-app 上压过一个具名键。
     * An unreachable platform does not stop the caller.
     * 平台连不上时不叫停调用方。
     */
    public void override(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("config key is missing");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("config value is missing");
        }
        HttpRequest request = HttpRequest.newBuilder(entries)
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ConfigEntryJson.overrideBody(key, value)))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 204 || code == 200) {
                return;
            }
            if (code == 401 || code == 403) {
                throw new IllegalStateException("config refused the operator");
            }
            throw new IllegalStateException("config rejected the override");
        } catch (IOException ex) {
            // Unreachable platform: the caller still has local application config.
            // 平台连不上：调用方仍有本地应用配置。
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
