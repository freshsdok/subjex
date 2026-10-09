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
import java.util.concurrent.ConcurrentHashMap;

/**
 * HttpConfigSource — HTTP 配置来源：读/写 platform-app 条目；可选 ETag 条件（Config-5c）。
 * <p>
 * {@link #lookup} / plain {@link #read} stay unconditional so a 304 cannot be mistaken for a miss.
 * {@link #readIfNoneMatch} sends {@code If-None-Match}; {@link #override} sends {@code If-Match}
 * when a revision was seen for that key.
 * {@link #lookup} 与普通 {@link #read} 不带条件，避免 304 被当成键缺失。
 */
public final class HttpConfigSource implements ConfigSource {

    public static final String ENTRIES_PATH = "/config/entries";

    private final URI entries;
    private final String authorization;
    private final Duration timeout;
    private final HttpClient client;
    /** Last seen revision per key (default namespace) — 默认命名空间下每键最近见过的修订号。 */
    private final ConcurrentHashMap<String, Long> knownRevisions = new ConcurrentHashMap<>();

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

    /** Unconditional read — 无条件读取。 */
    public Optional<ConfigEntry> read(String key) {
        return read(key, false);
    }

    /**
     * Conditional read: sends {@code If-None-Match} from {@link #knownRevision(String)} when present.
     * {@code 304} → empty (unchanged); does not clear the known revision.
     * 条件读取：有已知修订则发 If-None-Match；304 表示未变（空 Optional，保留已知修订）。
     */
    public Optional<ConfigEntry> readIfNoneMatch(String key) {
        return read(key, true);
    }

    public Optional<Long> knownRevision(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(knownRevisions.get(key));
    }

    private Optional<ConfigEntry> read(String key, boolean conditional) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        URI target = URI.create(entries + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .GET();
        if (conditional) {
            Long known = knownRevisions.get(key);
            if (known != null) {
                builder.header(ConfigETags.HEADER_IF_NONE_MATCH, ConfigETags.ofRevision(known));
            }
        }
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 401 || code == 403) {
                throw new IllegalStateException("config refused the operator");
            }
            if (code == 304) {
                return Optional.empty();
            }
            if (code == 404) {
                knownRevisions.remove(key);
                return Optional.empty();
            }
            if (code != 200) {
                return Optional.empty();
            }
            ConfigEntry entry = ConfigEntryJson.parse(response.body());
            long revision = entry.revision() > 0
                    ? entry.revision()
                    : etagRevision(response).orElse(entry.revision());
            knownRevisions.put(key, revision);
            return Optional.of(entry);
        } catch (IOException | IllegalArgumentException ex) {
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Store one named override. Sends {@code If-Match} when a revision is known for the key.
     * 写入覆盖；若已知该键修订号则带 If-Match。
     */
    public void override(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("config key is missing");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("config value is missing");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(entries)
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json");
        Long known = knownRevisions.get(key);
        if (known != null) {
            builder.header(ConfigETags.HEADER_IF_MATCH, ConfigETags.ofRevision(known));
        }
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(ConfigEntryJson.overrideBody(key, value)))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 204 || code == 200) {
                etagRevision(response).ifPresentOrElse(
                        rev -> knownRevisions.put(key, rev),
                        () -> knownRevisions.compute(key, (k, prev) -> prev == null ? 1L : prev + 1L));
                return;
            }
            if (code == 412) {
                throw new IllegalStateException("config revision mismatch");
            }
            if (code == 401 || code == 403) {
                throw new IllegalStateException("config refused the operator");
            }
            throw new IllegalStateException("config rejected the override");
        } catch (IOException ex) {
            // Unreachable platform: the caller still has local application config.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static Optional<Long> etagRevision(HttpResponse<?> response) {
        String etag = response.headers().firstValue(ConfigETags.HEADER_ETAG).orElse(null);
        if (etag == null) {
            etag = response.headers().firstValue("etag").orElse(null);
        }
        var parsed = ConfigETags.parseOne(etag);
        return parsed.isPresent() ? Optional.of(parsed.getAsLong()) : Optional.empty();
    }
}
