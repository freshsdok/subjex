package com.subjex.platform.app.organization;

/**
 * Organization — real-world / business org row (O1/O2 ontology).
 * Not the legacy tenant {@code org_unit} table (dropped after O8 / V24).
 * 现实/业务组织一行（O1/O2 本体）。不是已随 O8/V24 DROP 的旧租户 {@code org_unit} 表。
 */
public record Organization(String organizationId, String organizationName, String organizationState) {}
