package com.subjex.platform.app.entity;

import java.util.Map;
import java.util.Objects;

/**
 * AttrsPayloadLimits — hybrid {@code attrs} 体积门禁（ES-2 / ADR 0002）：大载荷须走 blob/ObjectStorage。
 * <p>
 * Fail-closed: reject oversized JSON or oversized string fields so callers externalize via
 * {@link EntityBlobStore}. Binary bytes must never be stuffed into attrs.
 * 失败关闭：JSON 或单字段字符串过大则拒绝，改走 {@link EntityBlobStore}；禁止把二进制塞进 attrs。
 */
public final class AttrsPayloadLimits {

    /** Soft cap under entity_record.attrs VARCHAR(16000) — 低于表列上限的软顶。 */
    public static final int MAX_ATTRS_JSON_CHARS = 12_000;

    /** Max characters for one string field value in attrs — attrs 内单字符串字段上限。 */
    public static final int MAX_STRING_FIELD_CHARS = 4_096;

    private AttrsPayloadLimits() {}

    /**
     * Validate accepted field map before JSON write — 写 JSON 前校验已接受字段图。
     *
     * @throws IllegalArgumentException when a string field or total JSON would be too large
     */
    public static void requireWithinLimits(Map<String, Object> accepted, String attrsJson) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(attrsJson, "attrsJson");
        for (Map.Entry<String, Object> entry : accepted.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text && text.length() > MAX_STRING_FIELD_CHARS) {
                throw new IllegalArgumentException(
                        "field too large for attrs (use EntityBlobStore): "
                                + entry.getKey()
                                + " maxChars="
                                + MAX_STRING_FIELD_CHARS);
            }
            if (value instanceof byte[]) {
                throw new IllegalArgumentException(
                        "binary payloads must not go into attrs (use EntityBlobStore): " + entry.getKey());
            }
        }
        if (attrsJson.length() > MAX_ATTRS_JSON_CHARS) {
            throw new IllegalArgumentException(
                    "attrs JSON too large (use EntityBlobStore for large fields); maxChars="
                            + MAX_ATTRS_JSON_CHARS);
        }
    }
}
