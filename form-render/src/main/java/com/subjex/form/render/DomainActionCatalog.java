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
 * DomainActionCatalog — 领域动作目录：从检入的 YAML 读出允许的领域动作键与字段约束。
 * <p>
 * Fail-closed: only keys listed here (and known to {@link DomainActionKey}) may appear on a form.
 * The catalog is checked in; there is no online editor.
 * 失败关闭：只有这里列出且 {@link DomainActionKey} 认识的键才能出现在表单上。
 * 目录检入仓库，没有在线编辑器。
 */
public final class DomainActionCatalog {

    /** Classpath path of the checked-in catalog — 检入目录的 classpath 路径。 */
    public static final String RESOURCE = "/actions/domain-action-catalog.yaml";

    private static final Set<String> ROOT_KEYS = Set.of("actions");
    private static final Set<String> ACTION_KEYS = Set.of("key", "titleEn", "titleZh", "fields");
    private static final Set<String> FIELD_KEYS = Set.of("name", "required");

    private final Map<String, DomainActionSpec> byKey;

    public DomainActionCatalog() {
        this(loadDefaultYaml());
    }

    DomainActionCatalog(String yaml) {
        this.byKey = parse(yaml);
    }

    /**
     * Spec for this key, or refuse — 该键的规格；没有则拒绝。
     */
    public DomainActionSpec require(String key) {
        DomainActionSpec spec = byKey.get(key);
        if (spec == null) {
            throw new FormDefinitionRejected("unknown domainAction " + key);
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
     * Every listed action, catalog order — 目录中的每一项，按目录顺序。
     */
    public List<DomainActionSpec> list() {
        return List.copyOf(byKey.values());
    }

    private static String loadDefaultYaml() {
        try (InputStream in = DomainActionCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("domain-action catalog missing at " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Map<String, DomainActionSpec> parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new FormDefinitionRejected("domain-action catalog is missing");
        }
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(loaded instanceof Map<?, ?> document)) {
            throw new FormDefinitionRejected("domain-action catalog must be a mapping");
        }
        rejectUnknown(document, ROOT_KEYS, "catalog");
        Object rawActions = document.get("actions");
        if (!(rawActions instanceof List<?> actionList) || actionList.isEmpty()) {
            throw new FormDefinitionRejected("actions must be a non-empty list");
        }
        Map<String, DomainActionSpec> loadedSpecs = new LinkedHashMap<>();
        for (Object raw : actionList) {
            if (!(raw instanceof Map<?, ?> action)) {
                throw new FormDefinitionRejected("each catalog action must be a mapping");
            }
            DomainActionSpec spec = actionSpec(action);
            DomainActionSpec previous = loadedSpecs.put(spec.key().key(), spec);
            if (previous != null) {
                throw new FormDefinitionRejected("duplicate domainAction " + spec.key().key());
            }
        }
        for (DomainActionKey known : DomainActionKey.values()) {
            if (!loadedSpecs.containsKey(known.key())) {
                throw new FormDefinitionRejected("catalog is missing required key " + known.key());
            }
        }
        return Map.copyOf(loadedSpecs);
    }

    private static DomainActionSpec actionSpec(Map<?, ?> action) {
        rejectUnknown(action, ACTION_KEYS, "catalog action");
        DomainActionKey key = DomainActionKey.parse(text(required(action, "key"), "key"));
        String titleEn = text(required(action, "titleEn"), "titleEn");
        String titleZh = text(required(action, "titleZh"), "titleZh");
        Object rawFields = required(action, "fields");
        // Empty fields list is allowed (e.g. entity.record.upsert validates against the entity at runtime).
        // 允许空字段列表（例如 entity.record.upsert 在运行时按实体定义校验）。
        if (!(rawFields instanceof List<?> fieldList)) {
            throw new FormDefinitionRejected("fields must be a list");
        }
        Set<String> requiredFields = new LinkedHashSet<>();
        Set<String> optionalFields = new LinkedHashSet<>();
        for (Object rawField : fieldList) {
            if (!(rawField instanceof Map<?, ?> field)) {
                throw new FormDefinitionRejected("each field must be a mapping");
            }
            rejectUnknown(field, FIELD_KEYS, "field");
            String name = text(required(field, "name"), "name");
            Object requiredValue = required(field, "required");
            if (!(requiredValue instanceof Boolean requiredFlag)) {
                throw new FormDefinitionRejected("required must be true or false");
            }
            if (requiredFields.contains(name) || optionalFields.contains(name)) {
                throw new FormDefinitionRejected("duplicate field name " + name);
            }
            if (requiredFlag) {
                requiredFields.add(name);
            } else {
                optionalFields.add(name);
            }
        }
        return new DomainActionSpec(key, titleEn, titleZh, requiredFields, optionalFields);
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
