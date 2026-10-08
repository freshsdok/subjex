package com.subjex.platform.app.security;

/**
 * OidcClaims — 从 IdP 校验后的身份声明（issuer + sub；email 仅作审计提示）。
 */
public record OidcClaims(String issuer, String subject, String email) {}
