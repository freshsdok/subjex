package com.subjex.platform.app.organization;

/**
 * Membership — Subject belongs to Organization. No tenant; no permission.
 * 成员关系：主体属于组织。不含租户、不授权限。
 */
public record Membership(String subjectId, String organizationId, String membershipState) {}
