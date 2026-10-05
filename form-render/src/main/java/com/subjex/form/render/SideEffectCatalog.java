package com.subjex.form.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * SideEffectCatalog — 副作用目录：从检入的 YAML 读出允许的副作用键与参数约束。
 * <p>
 * Fail-closed: only keys listed here (and known to {@link SideEffectKey}) may appear on a form.
 * The catalog is checked in; there is no online editor.
 * 失败关闭：只有这里列出且 {@link SideEffectKey} 认识的键才能出现在表单上。
 * 目录检入仓库，没有在线编辑器。
 */
public final class SideEffectCatalog {

    /** Classpath path of the checked-in catalog — 检入目录的 classpath 路径。 */
    public static final String RESOURCE = "/effects/side-effect-catalog.yaml";

    private static final Set<String> ROOT_KEYS = Set.of("effects");
    private static final Set<String> EFFECT_KEYS = Set.of("key", "titleEn", "titleZh", "params");
    private static final Set<String> PARAM_KEYS = Set.of("name", "required");

    private final Map<String, SideEffectSpec> byKey;

    public SideEffectCatalog() {
        this(loadDefaultYaml());
    }

    SideEffectCatalog(String yaml) {
        this.byKey = parse(yaml);
    }

    /**
     * Spec for this key, or refuse — 该键的规格；没有则拒绝。
     */
    public SideEffectSpec require(String key) {
        SideEffectSpec spec = byKey.get(key);
        if (spec == null) {
            throw new FormDefinitionRejected("unknown effect key " + key);
        }
        return spec;
    }

    /**
     * Whether the catalog lists this key — 目录是否列出该键。
     */
    public boolean knows(String key) {
        return byKey.containsKey(key);
    }

    /**
     * Every listed effect, catalog order — 目录中的每一项，按目录顺序。
     */
    public List<SideEffectSpec> list() {
        return List.copyOf(byKey.values());
    }

    private static String loadDefaultYaml() {
        try (InputStream in = SideEffectCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("side-effect catalog missing at " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Map<String, SideEffectSpec> parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new FormDefinitionRejected("side-effect catalog is missing");
        }
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(loaded instanceof Map<?, ?> document)) {
            throw new FormDefinitionRejected("side-effect catalog must be a mapping");
        }
        rejectUnknown(document, ROOT_KEYS, "catalog");
        Object rawEffects = document.get("effects");
        if (!(rawEffects instanceof List<?> effectList) || effectList.isEmpty()) {
            throw new FormDefinitionRejected("effects must be a non-empty list");
        }
        Map<String, SideEffectSpec> loadedSpecs = new LinkedHashMap<>();
        for (Object raw : effectList) {
            if (!(raw instanceof Map<?, ?> effect)) {
                throw new FormDefinitionRejected("each catalog effect must be a mapping");
            }
            SideEffectSpec spec = effectSpec(effect);
            SideEffectSpec previous = loadedSpecs.put(spec.key().key(), spec);
            if (previous != null) {
                throw new FormDefinitionRejected("duplicate effect key " + spec.key().key());
            }
        }
        for (SideEffectKey known : SideEffectKey.values()) {
            if (!loadedSpecs.containsKey(known.key())) {
                throw new FormDefinitionRejected("catalog is missing required key " + known.key());
            }
        }
        return Map.copyOf(loadedSpecs);
    }

    private static SideEffectSpec effectSpec(Map<?, ?> effect) {
        rejectUnknown(effect, EFFECT_KEYS, "catalog effect");
        SideEffectKey key = SideEffectKey.parse(text(required(effect, "key"), "key"));
        String titleEn = text(required(effect, "titleEn"), "titleEn");
        String titleZh = text(required(effect, "titleZh"), "titleZh");
        Object rawParams = required(effect, "params");
        if (!(rawParams instanceof List<?> paramList) || paramList.isEmpty()) {
            throw new FormDefinitionRejected("params must be a non-empty list");
        }
        Set<String> requiredParams = new LinkedHashSet<>();
        Set<String> optionalParams = new LinkedHashSet<>();
        for (Object rawParam : paramList) {
            if (!(rawParam instanceof Map<?, ?> param)) {
                throw new FormDefinitionRejected("each param must be a mapping");
            }
            rejectUnknown(param, PARAM_KEYS, "param");
            String name = text(required(param, "name"), "name");
            Object requiredValue = required(param, "required");
            if (!(requiredValue instanceof Boolean requiredFlag)) {
                throw new FormDefinitionRejected("required must be true or false");
            }
            if (requiredParams.contains(name) || optionalParams.contains(name)) {
                throw new FormDefinitionRejected("duplicate param name " + name);
            }
            if (requiredFlag) {
                requiredParams.add(name);
            } else {
                optionalParams.add(name);
            }
        }
        return new SideEffectSpec(key, titleEn, titleZh, requiredParams, optionalParams);
    }

    private static void rejectUnknown(Map<?, ?> map, Set<String> allowed, String label) {
        for (Object key : map.keySet()) {
            if (!(key instanceof String name) || !allowed.contains(name)) {
                throw new FormDefinitionRejected(label + " has an unknown key");
            }
        }
    }

    private static Object required(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new FormDefinitionRejected(key + " is missing");
        }
        return value;
    }

    private static String text(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank() || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new FormDefinitionRejected(label + " must be a single line of text");
        }
        return text;
    }
}
