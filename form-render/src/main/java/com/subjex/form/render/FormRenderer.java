package com.subjex.form.render;

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
 * FormRenderer — 表单渲染器：读取一份声明式表单，产出校验过的字段列表。
 * <p>
 * Invalid definitions are rejected. The renderer does not store answers.
 * 不合法的定义会被拒绝。渲染器不保存填写结果。
 */
public final class FormRenderer {

    private static final Pattern FORM_KEY = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern FIELD_NAME = Pattern.compile("[a-z][A-Za-z0-9]*");
    private static final Set<String> FORM_KEYS = Set.of("formKey", "titleEn", "titleZh", "fields");
    private static final Set<String> FIELD_KEYS = Set.of("name", "kind", "required", "maxLength", "minimum", "maximum");

    /**
     * Render one form document — 渲染一份表单文档。
     */
    public RenderedForm render(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new FormDefinitionRejected("form definition is missing");
        }
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(loaded instanceof Map<?, ?> document)) {
            throw new FormDefinitionRejected("form definition must be a mapping");
        }
        rejectUnknown(document, FORM_KEYS, "form");
        String formKey = text(required(document, "formKey"), "formKey");
        if (!FORM_KEY.matcher(formKey).matches()) {
            throw new FormDefinitionRejected("formKey must be lowercase words separated by hyphens");
        }
        String titleEn = text(required(document, "titleEn"), "titleEn");
        String titleZh = text(required(document, "titleZh"), "titleZh");
        Object rawFields = required(document, "fields");
        if (!(rawFields instanceof List<?> fieldList) || fieldList.isEmpty()) {
            throw new FormDefinitionRejected("fields must be a non-empty list");
        }
        List<FormField> fields = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Object rawField : fieldList) {
            if (!(rawField instanceof Map<?, ?> field)) {
                throw new FormDefinitionRejected("each field must be a mapping");
            }
            fields.add(field(field, names));
        }
        return new RenderedForm(formKey, titleEn, titleZh, recordName(formKey), List.copyOf(fields));
    }

    private static FormField field(Map<?, ?> field, Set<String> names) {
        rejectUnknown(field, FIELD_KEYS, "field");
        String name = text(required(field, "name"), "name");
        if (!FIELD_NAME.matcher(name).matches()) {
            throw new FormDefinitionRejected("field name must be a lower camel identifier");
        }
        if (!names.add(name)) {
            throw new FormDefinitionRejected("duplicate field name " + name);
        }
        FieldKind kind = FieldKind.parse(text(required(field, "kind"), "kind"));
        Object requiredValue = required(field, "required");
        if (!(requiredValue instanceof Boolean required)) {
            throw new FormDefinitionRejected("required must be true or false");
        }
        Integer minimum = optionalInt(field, "minimum");
        Integer maximum = optionalInt(field, "maximum");
        Integer maxLength = optionalInt(field, "maxLength");
        if (kind == FieldKind.TEXT) {
            if (minimum != null || maximum != null) {
                throw new FormDefinitionRejected("text field " + name + " cannot set minimum or maximum");
            }
            if (maxLength != null && maxLength < 1) {
                throw new FormDefinitionRejected("maxLength must be at least 1");
            }
        } else {
            if (maxLength != null) {
                throw new FormDefinitionRejected("integer field " + name + " cannot set maxLength");
            }
            if (minimum != null && maximum != null && minimum > maximum) {
                throw new FormDefinitionRejected("minimum is greater than maximum");
            }
        }
        return new FormField(name, kind, required, minimum, maximum, maxLength);
    }

    static String recordName(String formKey) {
        StringBuilder builder = new StringBuilder();
        for (String part : formKey.split("-")) {
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
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
        throw new FormDefinitionRejected(key + " must be an integer");
    }
}
