package com.subjex.platform.app.security;

import java.time.Instant;

/**
 * OidcPendingLogin — 尚未换码的 PKCE 登录态（state 对应 code_verifier + nonce）。
 */
public record OidcPendingLogin(String state, String codeVerifier, String nonce, Instant expiresAt) {}
