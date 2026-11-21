/**
 * Export hook — personal-data export request and approval (WP8-2 / ADR 0020). Artifact generation is WP8-3.
 * 个人数据导出申请与审批。产物生成留到 WP8-3。
 * <p>
 * Not OAuth consent, not tenant-level ZIP export, and not a legal engine. Sensitivity is {@code NORMAL} or {@code SENSITIVE}.
 * 不是 OAuth 同意，不是租户级 ZIP 导出，也不是法律判定器。敏感级别仅为 NORMAL / SENSITIVE。
 */
package com.subjex.platform.contract.exporthook;
