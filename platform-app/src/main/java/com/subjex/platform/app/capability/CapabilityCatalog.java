package com.subjex.platform.app.capability;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * CapabilityCatalog — 算法/AI 能力目录：从 classpath YAML 加载，失败关闭。
 * <p>
 * Loads {@code /capabilities/algorithm-catalog.yaml} and {@code /capabilities/ai-catalog.yaml}.
 * Every {@link CapabilityId} must appear exactly once; unknown ids in YAML are rejected.
 * 加载算法与 AI 两份 YAML；每个 {@link CapabilityId} 必须恰好出现一次；YAML 中未知 id 拒绝。
 */
public final class CapabilityCatalog {

    public static final String ALGORITHM_RESOURCE = "/capabilities/algorithm-catalog.yaml";
    public static final String AI_RESOURCE = "/capabilities/ai-catalog.yaml";

    private static final Set<String> ROOT_KEYS = Set.of("capabilities");
    private static final Set<String> ENTRY_KEYS = Set.of("id", "titleEn", "titleZh", "summaryEn", "summaryZh");

    private final Map<String, CapabilitySpec> byId;

    public CapabilityCatalog() {
        this(loadResource(ALGORITHM_RESOURCE), loadResource(AI_RESOURCE));
    }

    CapabilityCatalog(String algorithmYaml, String aiYaml) {
        Map<String, CapabilitySpec> loaded = new LinkedHashMap<>();
        merge(loaded, parse(algorithmYaml, CapabilityKind.ALGORITHM, ALGORITHM_RESOURCE));
        merge(loaded, parse(aiYaml, CapabilityKind.AI, AI_RESOURCE));
        for (CapabilityId known : CapabilityId.values()) {
            CapabilitySpec spec = loaded.get(known.id());
            if (spec == null) {
                throw new CapabilityRejected("catalog is missing required id " + known.id());
            }
            if (spec.kind() != known.kind()) {
                throw new CapabilityRejected("capability " + known.id() + " has wrong kind");
            }
        }
        this.byId = Map.copyOf(loaded);
    }

    /**
     * Spec for this id, or refuse — 该 id 的规格；没有则拒绝。
     */
    public CapabilitySpec require(String id) {
        CapabilitySpec spec = byId.get(id);
        if (spec == null) {
            throw new CapabilityRejected("unknown capability " + id);
        }
        return spec;
    }

    /**
     * Whether the catalog lists this id — 目录是否列出该 id。
     */
    public boolean knows(String id) {
        return byId.containsKey(id);
    }

    /**
     * Every listed capability, ALGORITHM then AI, then id — 目录中的每一项：先算法后 AI，再按 id。
     */
    public List<CapabilitySpec> list() {
        return byId.values().stream()
                .sorted(java.util.Comparator
                        .comparing((CapabilitySpec s) -> s.kind().ordinal())
                        .thenComparing(CapabilitySpec::id))
                .toList();
    }

    private static void merge(Map<String, CapabilitySpec> target, List<CapabilitySpec> specs) {
        for (CapabilitySpec spec : specs) {
            CapabilitySpec previous = target.put(spec.id(), spec);
            if (previous != null) {
                throw new CapabilityRejected("duplicate capability " + spec.id());
            }
        }
    }

    private static List<CapabilitySpec> parse(String yaml, CapabilityKind kind, String label) {
        if (yaml == null || yaml.isBlank()) {
            throw new CapabilityRejected(label + " is missing");
        }
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(loaded instanceof Map<?, ?> document)) {
            throw new CapabilityRejected(label + " must be a mapping");
        }
        rejectUnknown(document, ROOT_KEYS, label);
        Object rawList = document.get("capabilities");
        if (!(rawList instanceof List<?> entries) || entries.isEmpty()) {
            throw new CapabilityRejected(label + " capabilities must be a non-empty list");
        }
        List<CapabilitySpec> specs = new ArrayList<>();
        for (Object raw : entries) {
            if (!(raw instanceof Map<?, ?> entry)) {
                throw new CapabilityRejected("each capability must be a mapping");
            }
            rejectUnknown(entry, ENTRY_KEYS, "capability");
            CapabilityId known = CapabilityId.parse(text(required(entry, "id"), "id"));
            if (known.kind() != kind) {
                throw new CapabilityRejected("capability " + known.id() + " belongs in the " + known.kind() + " catalog");
            }
            specs.add(new CapabilitySpec(
                    known.id(),
                    kind,
                    text(required(entry, "titleEn"), "titleEn"),
                    text(required(entry, "titleZh"), "titleZh"),
                    text(required(entry, "summaryEn"), "summaryEn"),
                    text(required(entry, "summaryZh"), "summaryZh")));
        }
        return specs;
    }

    private static String loadResource(String resource) {
        try (InputStream in = CapabilityCatalog.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("capability catalog missing at " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void rejectUnknown(Map<?, ?> map, Set<String> allowed, String label) {
        for (Object key : map.keySet()) {
            if (!(key instanceof String name) || !allowed.contains(name)) {
                throw new CapabilityRejected(label + " has an unknown key");
            }
        }
    }

    private static Object required(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new CapabilityRejected(key + " is missing");
        }
        return value;
    }

    private static String text(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new CapabilityRejected(label + " must be non-blank text");
        }
        return text;
    }
}
