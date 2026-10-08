package com.subjex.platform.app.form;

import com.subjex.entity.generated.ServiceNote;
import com.subjex.entity.generated.ServiceNoteStore;
import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import java.util.Map;
import java.util.Objects;

/**
 * FormDomainActionRunner — 表单领域动作执行器：按声明的 {@code domainAction} 调用已有目录 / 实体桩，不再按 formKey 分支。
 * <p>
 * Allowed keys come from the checked-in catalog. New forms that reuse an existing key need no Java change;
 * a new key still needs one enum + switch arm. {@code entity.serviceNote.save} writes the JDBC
 * {@link ServiceNoteStore} (Flyway V11).
 * 允许的键来自检入目录。复用已有键的新表单不必改 Java；新增键仍需枚举与分支。
 * {@code entity.serviceNote.save} 写入 JDBC {@link ServiceNoteStore}（Flyway V11）。
 */
public final class FormDomainActionRunner {

    private final ServiceCatalog services;
    private final ConfigCatalog config;
    private final ServiceNoteStore serviceNotes;

    public FormDomainActionRunner(ServiceCatalog services, ConfigCatalog config, ServiceNoteStore serviceNotes) {
        this.services = Objects.requireNonNull(services, "services");
        this.config = Objects.requireNonNull(config, "config");
        this.serviceNotes = Objects.requireNonNull(serviceNotes, "serviceNotes");
    }

    /**
     * Apply the form's declared domain action — 执行表单声明的领域动作。
     *
     * @return short result summary for submission history / 提交历史用的短摘要
     */
    public String apply(RenderedForm form, Map<String, Object> accepted) {
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(accepted, "accepted");
        DomainActionKey key = form.domainAction();
        return switch (key) {
            case REGISTRY_REGISTER -> registerService(accepted);
            case CONFIG_OVERRIDE -> overrideConfig(accepted);
            case ENTITY_SERVICE_NOTE_SAVE -> saveServiceNote(accepted);
        };
    }

    private String registerService(Map<String, Object> accepted) {
        String serviceName = stringValue(accepted, "serviceName");
        String host = stringValue(accepted, "host");
        int port = intValue(accepted, "port");
        services.register(new ServiceEndpoint(serviceName, host, port));
        return serviceName + "@" + host + ":" + port;
    }

    private String overrideConfig(Map<String, Object> accepted) {
        String configKey = stringValue(accepted, "configKey");
        String configValue = stringValue(accepted, "configValue");
        config.override(configKey, configValue);
        return configKey + "=" + configValue;
    }

    private String saveServiceNote(Map<String, Object> accepted) {
        String noteId = stringValue(accepted, "noteId");
        String title = stringValue(accepted, "title");
        String body = optionalString(accepted, "body");
        Integer priority = optionalInt(accepted, "priority");
        serviceNotes.save(new ServiceNote(noteId, title, body, priority));
        return noteId + ":" + title;
    }

    private static String stringValue(Map<String, Object> values, String name) {
        return Objects.toString(values.get(name), "");
    }

    private static String optionalString(Map<String, Object> values, String name) {
        Object raw = values.get(name);
        if (raw == null) {
            return null;
        }
        String text = Objects.toString(raw, "").strip();
        return text.isEmpty() ? null : text;
    }

    private static int intValue(Map<String, Object> values, String name) {
        Object raw = values.get(name);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("field " + name + " must be a whole number");
    }

    private static Integer optionalInt(Map<String, Object> values, String name) {
        Object raw = values.get(name);
        if (raw == null || "".equals(raw)) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("field " + name + " must be a whole number");
    }
}
