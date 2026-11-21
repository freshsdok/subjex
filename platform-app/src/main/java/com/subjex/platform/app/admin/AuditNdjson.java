package com.subjex.platform.app.admin;

import com.subjex.platform.contract.audit.AuditEntry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * AuditNdjson — stable NDJSON field set for export and the file/stdout sink (WP9-1).
 * 导出与文件/标准输出 sink 共用的稳定 NDJSON 字段集。
 * <p>
 * Field names are part of the published schema. Records contain no secrets or tokens.
 * 字段名属于已发布 schema。记录不含密钥或令牌。
 */
public final class AuditNdjson {

    /** Stable field names, in write order — 稳定字段名，按写出顺序. */
    public static final List<String> FIELD_NAMES = List.of(
            "auditEntryId",
            "occurredAt",
            "tenantId",
            "actorIdentityId",
            "actorLogin",
            "actionName",
            "actionTarget",
            "outcome",
            "entityKey",
            "declarationVersion",
            "resolutionSource");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditNdjson() {}

    public static Map<String, Object> fields(
            String auditEntryId,
            Instant occurredAt,
            String tenantId,
            String actorIdentityId,
            String actorLogin,
            String actionName,
            String actionTarget,
            String outcome,
            String entityKey,
            Integer declarationVersion,
            String resolutionSource) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("auditEntryId", auditEntryId);
        row.put("occurredAt", occurredAt == null ? null : occurredAt.toString());
        row.put("tenantId", tenantId);
        row.put("actorIdentityId", actorIdentityId);
        row.put("actorLogin", actorLogin);
        row.put("actionName", actionName);
        row.put("actionTarget", actionTarget);
        row.put("outcome", outcome);
        row.put("entityKey", entityKey);
        row.put("declarationVersion", declarationVersion);
        row.put("resolutionSource", resolutionSource);
        return row;
    }

    public static Map<String, Object> fields(AuditRow row) {
        return fields(
                row.auditEntryId(),
                row.occurredAt(),
                row.tenantId(),
                row.actorIdentityId(),
                row.actorLogin(),
                row.actionName(),
                row.actionTarget(),
                row.outcome(),
                row.entityKey(),
                row.declarationVersion(),
                row.resolutionSource());
    }

    public static Map<String, Object> fields(AuditEntry entry) {
        return fields(
                entry.auditEntryId(),
                entry.occurredAt(),
                entry.tenantId(),
                entry.actorIdentityId(),
                null,
                entry.actionName(),
                entry.actionTarget(),
                entry.outcome() == null ? null : entry.outcome().name(),
                entry.entityKey(),
                entry.declarationVersion(),
                entry.resolutionSource());
    }

    public static byte[] lines(List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> row : rows) {
            out.append(line(row)).append('\n');
        }
        return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static String line(Map<String, Object> row) {
        try {
            return MAPPER.writeValueAsString(row);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to write audit NDJSON", ex);
        }
    }
}
