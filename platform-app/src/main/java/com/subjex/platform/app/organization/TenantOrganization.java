package com.subjex.platform.app.organization;

/**
 * TenantOrganization — Tenant ↔ Organization association. Does not grant permission.
 * 租户↔组织关联。不授权限。
 */
public record TenantOrganization(String tenantId, String organizationId, String linkState) {}
