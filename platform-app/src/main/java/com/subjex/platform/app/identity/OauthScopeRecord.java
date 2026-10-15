package com.subjex.platform.app.identity;

/** Catalog scope row — 范围目录行。 */
public record OauthScopeRecord(
        String scopeName, String descriptionEn, String descriptionZh, String permissionName) {}
