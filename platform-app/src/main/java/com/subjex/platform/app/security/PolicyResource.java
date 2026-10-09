package com.subjex.platform.app.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * PolicyResource — 策略引擎资源（Cedar resource / Casbin obj 子集）。
 * <p>
 * {@code kind} + {@code id} plus optional attributes ({@link #ATTR_ORGANIZATION_ID},
 * {@link #ATTR_TENANT_ID}). No custom DSL — flat string attributes only.
 * kind+id，另加可选属性。无自研 DSL，仅扁平字符串属性。
 */
public record PolicyResource(String kind, String id, Map<String, String> attributes) {

    /** Canonical organization id for scope checks (O8-2). */
    public static final String ATTR_ORGANIZATION_ID = "organizationId";


    /** Optional tenant on the resource — 资源上可选租户。 */
    public static final String ATTR_TENANT_ID = "tenantId";

    public PolicyResource {
        kind = kind == null ? "" : kind.trim();
        id = id == null ? "" : id.trim();
        attributes = attributes == null || attributes.isEmpty()
                ? Map.of()
                : Map.copyOf(normalize(attributes));
    }

    public static PolicyResource of(String kind, String id) {
        return new PolicyResource(kind, id, Map.of());
    }

    public PolicyResource withAttribute(String key, String value) {
        Objects.requireNonNull(key, "key");
        Map<String, String> next = new LinkedHashMap<>(attributes);
        if (value == null || value.isBlank()) {
            next.remove(key.trim());
        } else {
            next.put(key.trim(), value.trim());
        }
        return new PolicyResource(kind, id, next);
    }

    public String attribute(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return attributes.get(key.trim());
    }

    /** Organization id for scope checks — only {@link #ATTR_ORGANIZATION_ID}. */
    public String organizationId() {
        return attribute(ATTR_ORGANIZATION_ID);
    }

    private static Map<String, String> normalize(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                continue;
            }
            if (e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            out.put(e.getKey().trim(), e.getValue().trim());
        }
        return out;
    }
}
