package com.subjex.page.declare;

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
 * PageRenderer — 页面渲染器：读取一份声明式流程，产出校验过的列表 / 详情 / 提交描述。
 * <p>
 * Invalid definitions are rejected. The renderer does not call APIs or draw a designer.
 * 不合法的定义会被拒绝。渲染器不调 API，也不画设计器。
 */
public final class PageRenderer {

    private static final Pattern FLOW_KEY = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern FORM_KEY = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern ENTITY_KEY = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern CONSOLE_PATH = Pattern.compile("/pages(/[a-z0-9][a-z0-9-]*)+");
    private static final Pattern DETAIL_PATH =
            Pattern.compile("/pages(/[a-z0-9][a-z0-9-]*)+/\\{id\\}");
    private static final Pattern API_PATH = Pattern.compile("/api/v1(/[A-Za-z0-9._~!$&'()*+,;=:@%-]+)+");
    private static final Pattern FIELD_NAME = Pattern.compile("[a-z][A-Za-z0-9]*");

    private static final Pattern PERMISSION = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+");
    private static final Set<String> FLOW_KEYS = Set.of(
            "flowKey", "titleEn", "titleZh", "formKey", "entityKey", "version", "permission", "tenantScoped", "list", "detail", "submit");
    private static final Set<String> LIST_KEYS = Set.of("path", "apiPath", "itemsKey", "blocks");
    private static final Set<String> DETAIL_KEYS = Set.of("path", "apiPath", "itemsKey", "idField", "blocks");
    private static final Set<String> SUBMIT_KEYS = Set.of("path", "apiPath", "redirectTo", "blocks");

    /** First-wave page-block catalog ids — 首波页面积木目录 id（与 page-block-catalog.yaml 对齐）。 */
    private static final Set<String> CATALOG_BLOCK_IDS = Set.of(
            "ListTable",
            "FormFields",
            "DetailReadonly",
            "Section",
            "Tabs",
            "SubmitBar",
            "UserPicker",
            "OrgPicker",
            "FlowSorter");

    /**
     * Render one flow document — 渲染一份流程文档。
     */
    public RenderedFlow render(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new PageDefinitionRejected("flow definition is missing");
        }
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(loaded instanceof Map<?, ?> document)) {
            throw new PageDefinitionRejected("flow definition must be a mapping");
        }
        rejectUnknown(document, FLOW_KEYS, "flow");
        String flowKey = text(required(document, "flowKey"), "flowKey");
        if (!FLOW_KEY.matcher(flowKey).matches()) {
            throw new PageDefinitionRejected("flowKey must be lowercase words separated by hyphens");
        }
        String titleEn = text(required(document, "titleEn"), "titleEn");
        String titleZh = text(required(document, "titleZh"), "titleZh");
        String formKey = optionalText(document, "formKey");
        if (formKey != null && !FORM_KEY.matcher(formKey).matches()) {
            throw new PageDefinitionRejected("formKey must be lowercase words separated by hyphens");
        }
        String entityKey = optionalText(document, "entityKey");
        if (entityKey != null && !ENTITY_KEY.matcher(entityKey).matches()) {
            throw new PageDefinitionRejected("entityKey must be lowercase words separated by hyphens");
        }
        int version = requiredVersion(document);
        String permission = parsePermission(required(document, "permission"));
        boolean tenantScoped = optionalBoolean(document, "tenantScoped");
        ListPageSpec list = list(mapping(required(document, "list"), "list"));
        DetailPageSpec detail = detail(mapping(required(document, "detail"), "detail"));
        SubmitPageSpec submit = submit(mapping(required(document, "submit"), "submit"));
        return new RenderedFlow(
                flowKey, titleEn, titleZh, formKey, entityKey, version, permission, tenantScoped, list, detail, submit);
    }

    private static ListPageSpec list(Map<?, ?> section) {
        rejectUnknown(section, LIST_KEYS, "list");
        String path = consolePath(text(required(section, "path"), "path"), "list.path");
        String apiPath = apiPath(text(required(section, "apiPath"), "apiPath"));
        String itemsKey = optionalFieldName(section, "itemsKey");
        List<String> blocks = parseBlocks(section, "list");
        return new ListPageSpec(path, apiPath, itemsKey, blocks);
    }

    private static DetailPageSpec detail(Map<?, ?> section) {
        rejectUnknown(section, DETAIL_KEYS, "detail");
        String path = text(required(section, "path"), "path");
        if (!DETAIL_PATH.matcher(path).matches()) {
            throw new PageDefinitionRejected("detail.path must be a /pages/.../{id} console path");
        }
        String apiPath = apiPath(text(required(section, "apiPath"), "apiPath"));
        String itemsKey = optionalFieldName(section, "itemsKey");
        String idField = text(required(section, "idField"), "idField");
        if (!FIELD_NAME.matcher(idField).matches()) {
            throw new PageDefinitionRejected("idField must be a lower camel identifier");
        }
        List<String> blocks = parseBlocks(section, "detail");
        return new DetailPageSpec(path, apiPath, itemsKey, idField, blocks);
    }

    private static SubmitPageSpec submit(Map<?, ?> section) {
        rejectUnknown(section, SUBMIT_KEYS, "submit");
        String path = consolePath(text(required(section, "path"), "path"), "submit.path");
        String apiPath = apiPath(text(required(section, "apiPath"), "apiPath"));
        String redirectTo = consolePath(text(required(section, "redirectTo"), "redirectTo"), "redirectTo");
        List<String> blocks = parseBlocks(section, "submit");
        return new SubmitPageSpec(path, apiPath, redirectTo, blocks);
    }

    private static String consolePath(String path, String label) {
        if (!CONSOLE_PATH.matcher(path).matches()) {
            throw new PageDefinitionRejected(label + " must be a /pages/... console path");
        }
        return path;
    }

    private static String apiPath(String path) {
        if (!API_PATH.matcher(path).matches()) {
            throw new PageDefinitionRejected("apiPath must be an /api/v1/... path");
        }
        return path;
    }

    private static String optionalFieldName(Map<?, ?> map, String key) {
        String value = optionalText(map, key);
        if (value == null) {
            return null;
        }
        if (!FIELD_NAME.matcher(value).matches()) {
            throw new PageDefinitionRejected(key + " must be a lower camel identifier");
        }
        return value;
    }

    private static Map<?, ?> mapping(Object value, String label) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new PageDefinitionRejected(label + " must be a mapping");
        }
        return map;
    }


    private static List<String> parseBlocks(Map<?, ?> section, String sectionLabel) {
        Object value = section.get("blocks");
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> raw)) {
            throw new PageDefinitionRejected(sectionLabel + ".blocks must be a list");
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        List<String> blocks = new ArrayList<>(raw.size());
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Object item : raw) {
            if (!(item instanceof String id) || id.isBlank() || id.indexOf('\n') >= 0 || id.indexOf('\r') >= 0) {
                throw new PageDefinitionRejected(sectionLabel + ".blocks entries must be single-line block ids");
            }
            if (!CATALOG_BLOCK_IDS.contains(id)) {
                throw new PageDefinitionRejected(sectionLabel + ".blocks has an unknown block id");
            }
            if (!seen.add(id)) {
                throw new PageDefinitionRejected(sectionLabel + ".blocks must not contain duplicate ids");
            }
            blocks.add(id);
        }
        return List.copyOf(blocks);
    }

    private static void rejectUnknown(Map<?, ?> map, Set<String> allowed, String label) {
        for (Object key : map.keySet()) {
            if (!(key instanceof String name) || !allowed.contains(name)) {
                throw new PageDefinitionRejected(label + " has an unknown key");
            }
        }
    }

    private static Object required(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new PageDefinitionRejected(key + " is missing");
        }
        return value;
    }

    private static String text(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank() || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new PageDefinitionRejected(label + " must be a single line of text");
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

    private static int requiredVersion(Map<?, ?> map) {
        Object value = required(map, "version");
        int version;
        if (value instanceof Integer number) {
            version = number;
        } else if (value instanceof Long number) {
            version = Math.toIntExact(number);
        } else {
            throw new PageDefinitionRejected("version must be an integer");
        }
        if (version < 1) {
            throw new PageDefinitionRejected("version must be at least 1");
        }
        return version;
    }

    private static String parsePermission(Object value) {
        String permission = text(value, "permission");
        if (!PERMISSION.matcher(permission).matches()) {
            throw new PageDefinitionRejected("permission must be dotted lowercase segments");
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
        throw new PageDefinitionRejected(key + " must be true or false");
    }
}
