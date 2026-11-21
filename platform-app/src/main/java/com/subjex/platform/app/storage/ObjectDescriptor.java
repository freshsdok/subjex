package com.subjex.platform.app.storage;

import java.time.Instant;

/**
 * ObjectDescriptor — registry row for an authorized-track object (owner required).
 * 授权轨对象登记行（必须有所有者）。不回填已有 entity_blob。
 *
 * @param objectId registry id — 登记标识
 * @param tenantId business tenant (never reserved {@code platform}) — 业务租户（不得为 platform）
 * @param objectKey storage object key under that tenant — 该租户下的对象键
 * @param ownerSubjectId owning subject — 所有者主体
 * @param createdAt when registered — 登记时间
 */
public record ObjectDescriptor(
        String objectId,
        String tenantId,
        String objectKey,
        String ownerSubjectId,
        Instant createdAt) {}
