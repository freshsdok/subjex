package com.subjex.platform.app.organization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * OrganizationIdMint — O3 stable organization_id from legacy org_unit.
 * <p>
 * Reuse {@code org_unit_id} when it appears in only one tenant; otherwise SHA-256 hex
 * of {@code tenantId + NUL + orgUnitId} (exactly 64 chars, fits VARCHAR(64)).
 * Never merges rows that only share {@code unit_name}.
 * 全局唯一则复用 org_unit_id；冲突则 SHA-256 十六进制。从不按同名合并。
 */
public final class OrganizationIdMint {

    private OrganizationIdMint() {}

    /**
     * @param orgUnitIdGloballyUnique true when this {@code org_unit_id} string exists in exactly one tenant
     */
    public static String mint(String tenantId, String orgUnitId, boolean orgUnitIdGloballyUnique) {
        String tid = requireNonBlank(tenantId, "tenantId");
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        if (orgUnitIdGloballyUnique) {
            return uid;
        }
        return sha256Hex(tid + '\0' + uid);
    }

    static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 required", e);
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return value.trim();
    }
}
