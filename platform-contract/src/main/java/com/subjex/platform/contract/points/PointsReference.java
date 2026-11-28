package com.subjex.platform.contract.points;

/**
 * Optional generic hang-on for a later business module (type + id). Same idea as payment references.
 * 可选的通用挂靠（类型 + 标识），与支付参考同一思路，供后续业务模块挂上。
 */
public record PointsReference(String type, String id) {

    public PointsReference {
        type = requireText(type, "reference type");
        id = requireText(id, "reference id");
        if (type.length() > 64) {
            throw new IllegalArgumentException("reference type too long");
        }
        if (id.length() > 128) {
            throw new IllegalArgumentException("reference id too long");
        }
    }

    static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
