package com.subjex.entity.declare;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * EntityRenderer — 实体渲染器：读取一份声明式实体，产出校验过的字段列表。
 * <p>
 * Invalid definitions are rejected. The renderer does not create tables or write files.
 * 不合法的定义会被拒绝。渲染器不建表也不写文件。
 */
public final class EntityRenderer {

    private static final Pattern ENTITY_KEY = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern TABLE_NAME = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)*");
    private static final Pattern FIELD_NAME = Pattern.compile("[a-z][A-Za-z0-9]*");
    private static final Pattern PERMISSION = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+");
    private static final Set<String> ENTITY_KEYS =
            Set.of("entityKey", "tableName", "version", "permission", "tenantScoped", "fields");
    private static final Set<String> FIELD_KEYS =
            Set.of("name", "kind", "required", "maxLength", "enumValues", "refEntityKey");

    /**
     * Render one entity document — 渲染一份实体文档。
     */
    public RenderedEntity render(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new EntityDefinitionRejected("entity definition is missing");
        }
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(loaded instanceof Map<?, ?> document)) {
            throw new EntityDefinitionRejected("entity definition must be a mapping");
        }
        rejectUnknown(document, ENTITY_KEYS, "entity");
        String entityKey = text(required(document, "entityKey"), "entityKey");
        if (!ENTITY_KEY.matcher(entityKey).matches()) {
            throw new EntityDefinitionRejected("entityKey must be lowercase words separated by hyphens");
        }
        String tableName = text(required(document, "tableName"), "tableName");
        if (!TABLE_NAME.matcher(tableName).matches()) {
            throw new EntityDefinitionRejected("tableName must be lowercase words separated by underscores");
        }
        int version = requiredVersion(document);
        String permission = parsePermission(required(document, "permission"));
        boolean tenantScoped = optionalBoolean(document, "tenantScoped");
        Object rawFields = required(document, "fields");
        if (!(rawFields instanceof List<?> fieldList) || fieldList.isEmpty()) {
            throw new EntityDefinitionRejected("fields must be a non-empty list");
        }
        List<EntityField> fields = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Object rawField : fieldList) {
            if (!(rawField instanceof Map<?, ?> field)) {
                throw new EntityDefinitionRejected("each field must be a mapping");
            }
            fields.add(field(field, names));
        }
        return new RenderedEntity(entityKey, tableName, version, permission, tenantScoped, typeName(entityKey), List.copyOf(fields));
    }

    private static EntityField field(Map<?, ?> field, Set<String> names) {
        rejectUnknown(field, FIELD_KEYS, "field");
        String name = text(required(field, "name"), "name");
        if (!FIELD_NAME.matcher(name).matches()) {
            throw new EntityDefinitionRejected("field name must be a lower camel identifier");
        }
        if (!names.add(name)) {
            throw new EntityDefinitionRejected("duplicate field name " + name);
        }
        EntityFieldKind kind = EntityFieldKind.parse(text(required(field, "kind"), "kind"));
        Object requiredValue = required(field, "required");
        if (!(requiredValue instanceof Boolean required)) {
            throw new EntityDefinitionRejected("required must be true or false");
        }
        Integer maxLength = optionalInt(field, "maxLength");
        if (kind.allowsMaxLength()) {
            if (maxLength != null && maxLength < 1) {
                throw new EntityDefinitionRejected("maxLength must be at least 1");
            }
        } else if (maxLength != null) {
            throw new EntityDefinitionRejected(kind.name().toLowerCase() + " field " + name + " cannot set maxLength");
        }
        List<String> enumValues = optionalStringList(field, "enumValues");
        if (kind == EntityFieldKind.ENUM) {
            if (enumValues.isEmpty()) {
                throw new EntityDefinitionRejected("enum field " + name + " requires non-empty enumValues");
            }
        } else if (!enumValues.isEmpty()) {
            throw new EntityDefinitionRejected("field " + name + " cannot set enumValues unless kind is enum");
        }
        String refEntityKey = optionalText(field, "refEntityKey");
        if (refEntityKey != null && kind != EntityFieldKind.ENTITY_REF) {
            throw new EntityDefinitionRejected("field " + name + " cannot set refEntityKey unless kind is entityRef");
        }
        if (refEntityKey != null && !ENTITY_KEY.matcher(refEntityKey).matches()) {
            throw new EntityDefinitionRejected("refEntityKey must be lowercase words separated by hyphens");
        }
        return new EntityField(name, kind, required, maxLength, enumValues, refEntityKey);
    }

    static String typeName(String entityKey) {
        StringBuilder builder = new StringBuilder();
        for (String part : entityKey.split("-")) {
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    static String toSnakeCase(String camel) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    builder.append('_');
                }
                builder.append(Character.toLowerCase(c));
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static void rejectUnknown(Map<?, ?> map, Set<String> allowed, String label) {
        for (Object key : map.keySet()) {
            if (!(key instanceof String name) || !allowed.contains(name)) {
                throw new EntityDefinitionRejected(label + " has an unknown key");
            }
        }
    }

    private static Object required(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new EntityDefinitionRejected(key + " is missing");
        }
        return value;
    }

    private static String text(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank() || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new EntityDefinitionRejected(label + " must be a single line of text");
        }
        return text;
    }

    private static String optionalText(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        return text(value, key);
    }

    private static List<String> optionalStringList(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new EntityDefinitionRejected(key + " must be a non-empty list");
        }
        List<String> out = new ArrayList<>(list.size());
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            String entry = text(item, key + " entry");
            if (!seen.add(entry)) {
                throw new EntityDefinitionRejected(key + " has a duplicate value");
            }
            out.add(entry);
        }
        return List.copyOf(out);
    }

    private static Integer optionalInt(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Long number) {
            return Math.toIntExact(number);
        }
        throw new EntityDefinitionRejected(key + " must be an integer");
    }

    private static int requiredVersion(Map<?, ?> map) {
        Object value = required(map, "version");
        int version;
        if (value instanceof Integer number) {
            version = number;
        } else if (value instanceof Long number) {
            version = Math.toIntExact(number);
        } else {
            throw new EntityDefinitionRejected("version must be an integer");
        }
        if (version < 1) {
            throw new EntityDefinitionRejected("version must be at least 1");
        }
        return version;
    }

    private static String parsePermission(Object value) {
        String permission = text(value, "permission");
        if (!PERMISSION.matcher(permission).matches()) {
            throw new EntityDefinitionRejected("permission must be dotted lowercase segments");
        }
        return permission;
    }

    private static boolean optionalBoolean(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new EntityDefinitionRejected(key + " must be true or false");
    }
}
