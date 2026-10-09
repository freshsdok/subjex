package com.subjex.platform.app.organization;

/**
 * Organization — real-world / business org row (O1/O2). Not tenant-scoped org_unit.
 * 现实/业务组织一行（O1/O2）。不是租户内 org_unit。
 */
public record Organization(String organizationId, String organizationName, String organizationState) {}
