package com.subjex.platform.app.storage;

import com.subjex.platform.contract.exporthook.SensitivityClass;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * ObjectGrantStore — register an authorized-track object (with owner) and its explicit grants.
 * 登记授权轨对象（含所有者）及其显式授权。
 * <p>
 * Authorization decisions live in {@link ObjectAccessAuthorizer}, above {@link
 * com.subjex.platform.contract.storage.ObjectStorage}. This store does not read or write blob
 * bytes and does not backfill {@code entity_blob}.
 * 访问判定在 Authorizer，位于对象存储端口之上。本存储不读写字节，不回填 entity_blob。
 */
public interface ObjectGrantStore {

    /** Reserved operator tenant — must never be a default home. / 保留操作员租户，不得作为默认归属。 */
    String RESERVED_TENANT = "platform";

    /**
     * Register an object with a required owner in an existing ACTIVE business tenant.
     * 在已存在的 ACTIVE 业务租户中登记对象，必须给出所有者。
     */
    ObjectDescriptor register(String tenantId, String objectKey, String ownerSubjectId);

    /** Look up a registered object — 查找已登记对象。 */
    Optional<ObjectDescriptor> find(String tenantId, String objectKey);

    /**
     * Look up a registered object by its globally unique registry id.
     * 按全局唯一的登记标识查找已登记对象。
     */
    Optional<ObjectDescriptor> findByObjectId(String objectId);

    /**
     * Grant the object to a subject in the same tenant. Idempotent on the same tuple.
     * 把对象授权给同一租户内的主体；同一元组幂等。
     */
    ObjectGrant grantToSubject(
            String tenantId, String objectKey, String granteeSubjectId, Instant expiresAt, String createdBySubjectId);

    /**
     * Grant the object to an organization already bound to the tenant. Idempotent on the same tuple.
     * 把对象授权给已绑定到该租户的组织；同一元组幂等。
     */
    ObjectGrant grantToOrganization(
            String tenantId, String objectKey, String organizationId, Instant expiresAt, String createdBySubjectId);

    /** Revoke one grant if present (no-op when missing) — 撤销一条授权；不存在则无操作。 */
    void revoke(String grantId);

    /** All grants for the object, including expired (caller filters) — 该对象全部授权行（含已过期，由调用方过滤）。 */
    List<ObjectGrant> listGrants(String tenantId, String objectKey);

    /**
     * Objects the subject owns or holds an unexpired SUBJECT grant in the tenant.
     * Does not include organization grants, inbox attachments, or unregistered entity_blob.
     * 该主体在租户内拥有或持有未过期 SUBJECT 授权的对象；不含组织授权、站内信附件或未登记 entity_blob。
     */
    List<ObjectDescriptor> listOwnedOrSubjectGranted(
            String tenantId, String subjectId, int limit, int offset);

    /**
     * Current sensitivity of a registered object; missing objects are rejected. Default at register is NORMAL.
     * 已登记对象的敏感级别；对象不存在则拒绝。登记时默认为 NORMAL。
     */
    SensitivityClass objectSensitivity(String tenantId, String objectKey);

    /**
     * Set sensitivity explicitly (NORMAL or SENSITIVE). Does not infer or reclassify implicitly.
     * 显式设置敏感级别。不会推断或悄悄改标。
     */
    void setObjectSensitivity(String tenantId, String objectKey, SensitivityClass sensitivity);

    /**
     * Reject a missing or reserved tenant id; never default to {@code platform}.
     * 拒绝缺失或保留租户；永不默认 platform。
     */
    static String requireExplicitBusinessTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String id = tenantId.trim();
        if (RESERVED_TENANT.equals(id)) {
            throw new IllegalArgumentException(
                    "reserved tenant " + RESERVED_TENANT + " is not an object-access home");
        }
        return id;
    }
}
