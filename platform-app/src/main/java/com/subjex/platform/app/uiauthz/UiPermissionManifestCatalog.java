package com.subjex.platform.app.uiauthz;

import com.subjex.platform.app.security.OperatorPermission;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * UiPermissionManifestCatalog — classpath UI 权限 Manifest 目录（ADR 0010 / PC-1）。
 * <p>
 * Loads {@code /ui-authz/console-ui.yaml}. Every {@code permission} / {@code anyOf} name must be in
 * {@link OperatorPermission} catalog; unknown → {@link UiPermissionRejected} (fail-closed).
 * 加载控制台 YAML；权限名必须在 OperatorPermission 目录内；未知失败关闭。
 */
public final class UiPermissionManifestCatalog {

    public static final String CONSOLE_RESOURCE = "/ui-authz/console-ui.yaml";

    private static final Set<String> ROOT_KEYS = Set.of("version", "client", "defaults", "entries");
    private static final Set<String> DEFAULT_KEYS = Set.of("lockedMenuEffect", "missingButtonEffect");
    private static final Set<String> ENTRY_KEYS =
            Set.of("id", "kind", "href", "route", "permission", "anyOf");

    private final Map<String, UiPermissionManifest> byClient;

    /** Load classpath console baseline — 加载 classpath 控制台基线。 */
    public UiPermissionManifestCatalog() {
        this(Map.of(UiPermissionManifest.CLIENT_CONSOLE, loadResource(CONSOLE_RESOURCE)));
    }

    /**
     * Test / dual-load hook: client → YAML body — 测试钩子：client → YAML 正文。
     */
    UiPermissionManifestCatalog(Map<String, String> clientYamlBodies) {
        Objects.requireNonNull(clientYamlBodies, "clientYamlBodies");
        if (clientYamlBodies.isEmpty()) {
            throw new UiPermissionRejected("ui-authz catalog requires at least one client YAML");
        }
        Map<String, UiPermissionManifest> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : clientYamlBodies.entrySet()) {
            UiPermissionManifest manifest = parse(e.getValue(), "ui-authz/" + e.getKey());
            if (!manifest.client().equals(e.getKey())) {
                throw new UiPermissionRejected(
                        "YAML client " + manifest.client() + " does not match key " + e.getKey());
            }
            UiPermissionManifest previous = loaded.put(manifest.client(), manifest);
            if (previous != null) {
                throw new UiPermissionRejected("duplicate client " + manifest.client());
            }
        }
        this.byClient = Map.copyOf(loaded);
    }

    /** Parse a single YAML body (tests) — 解析单份 YAML（测试）。 */
    public static UiPermissionManifest parseYaml(String yaml) {
        return parse(yaml, "inline");
    }

    /** Effective manifest for client, or refuse — 该 client 的 Manifest；没有则拒绝。 */
    public UiPermissionManifest require(String client) {
        UiPermissionManifest manifest = byClient.get(client == null ? null : client.trim());
        if (manifest == null) {
            throw new UiPermissionRejected("unknown ui-authz client " + client);
        }
        return manifest;
    }

    public boolean knows(String client) {
        return client != null && byClient.containsKey(client.trim());
    }

    /** All loaded clients — 已加载的全部 client。 */
    public List<UiPermissionManifest> list() {
        return List.copyOf(byClient.values());
    }

    private static UiPermissionManifest parse(String yaml, String label) {
        if (yaml == null || yaml.isBlank()) {
            throw new UiPermissionRejected(label + " is missing");
        }
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(loaded instanceof Map<?, ?> document)) {
            throw new UiPermissionRejected(label + " must be a mapping");
        }
        rejectUnknown(document, ROOT_KEYS, label);
        int version = intRequired(document.get("version"), "version");
        String client = text(required(document, "client"), "client");
        UiPermissionManifestDefaults defaults = parseDefaults(document.get("defaults"));
        Object rawEntries = document.get("entries");
        if (!(rawEntries instanceof List<?> entries) || entries.isEmpty()) {
            throw new UiPermissionRejected(label + " entries must be a non-empty list");
        }
        Set<String> seenIds = new LinkedHashSet<>();
        List<UiPermissionEntry> parsed = new ArrayList<>();
        for (Object raw : entries) {
            if (!(raw instanceof Map<?, ?> entry)) {
                throw new UiPermissionRejected("each entry must be a mapping");
            }
            UiPermissionEntry item = parseEntry(entry);
            if (!seenIds.add(item.id())) {
                throw new UiPermissionRejected("duplicate entry id " + item.id());
            }
            parsed.add(item);
        }
        return new UiPermissionManifest(version, client, defaults, parsed);
    }

    private static UiPermissionManifestDefaults parseDefaults(Object raw) {
        if (raw == null) {
            return UiPermissionManifestDefaults.lockedDefaults();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new UiPermissionRejected("defaults must be a mapping");
        }
        rejectUnknown(map, DEFAULT_KEYS, "defaults");
        String menu = optionalText(map.get("lockedMenuEffect"));
        String button = optionalText(map.get("missingButtonEffect"));
        return new UiPermissionManifestDefaults(
                menu == null ? UiPermissionManifestDefaults.EFFECT_GREY : menu,
                button == null ? UiPermissionManifestDefaults.EFFECT_DISABLE : button);
    }

    private static UiPermissionEntry parseEntry(Map<?, ?> entry) {
        rejectUnknown(entry, ENTRY_KEYS, "entry");
        String id = text(required(entry, "id"), "id");
        UiPermissionEntryKind kind = UiPermissionEntryKind.fromWire(text(required(entry, "kind"), "kind"));
        String href = optionalText(entry.get("href"));
        String route = optionalText(entry.get("route"));
        String permission = optionalText(entry.get("permission"));
        List<String> anyOf = parseAnyOf(entry.get("anyOf"));

        if (permission != null) {
            requireKnownPermission(permission);
        }
        for (String name : anyOf) {
            requireKnownPermission(name);
        }

        switch (kind) {
            case MENU -> {
                if (href == null) {
                    throw new UiPermissionRejected("menu entry " + id + " requires href");
                }
                if (route != null) {
                    throw new UiPermissionRejected("menu entry " + id + " must not set route");
                }
            }
            case PAGE -> {
                if (route == null) {
                    throw new UiPermissionRejected("page entry " + id + " requires route");
                }
                if (href != null) {
                    throw new UiPermissionRejected("page entry " + id + " must not set href");
                }
            }
            case BUTTON -> {
                if (href != null || route != null) {
                    throw new UiPermissionRejected("button entry " + id + " must not set href/route");
                }
                if (permission == null && anyOf.isEmpty()) {
                    throw new UiPermissionRejected(
                            "button entry " + id + " requires permission or anyOf");
                }
            }
            default -> throw new UiPermissionRejected("unsupported kind " + kind);
        }

        return new UiPermissionEntry(id, kind, href, route, permission, anyOf);
    }

    private static List<String> parseAnyOf(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new UiPermissionRejected("anyOf must be a non-empty list when present");
        }
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            String name = text(item, "anyOf item");
            if (!seen.add(name)) {
                throw new UiPermissionRejected("duplicate anyOf permission " + name);
            }
            names.add(name);
        }
        return names;
    }

    private static void requireKnownPermission(String permissionName) {
        if (!OperatorPermission.allPermissionNames().contains(permissionName)) {
            throw new UiPermissionRejected(
                    "unknown permission " + permissionName + " (not in OperatorPermission catalog)");
        }
    }

    private static String loadResource(String resource) {
        try (InputStream in = UiPermissionManifestCatalog.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("ui-authz manifest missing at " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void rejectUnknown(Map<?, ?> map, Set<String> allowed, String label) {
        for (Object key : map.keySet()) {
            if (!(key instanceof String name) || !allowed.contains(name)) {
                throw new UiPermissionRejected(label + " has an unknown key: " + key);
            }
        }
    }

    private static Object required(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new UiPermissionRejected(key + " is missing");
        }
        return value;
    }

    private static String text(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new UiPermissionRejected(label + " must be non-blank text");
        }
        return text.trim();
    }

    private static String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new UiPermissionRejected("optional text field must be a string");
        }
        return text.isBlank() ? null : text.trim();
    }

    private static int intRequired(Object value, String label) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        throw new UiPermissionRejected(label + " must be an integer");
    }
}
