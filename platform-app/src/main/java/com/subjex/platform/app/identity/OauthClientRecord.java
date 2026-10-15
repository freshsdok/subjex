package com.subjex.platform.app.identity;

import java.util.Arrays;
import java.util.List;

/** Registered OAuth client row — 已注册 OAuth 客户端行。 */
public record OauthClientRecord(
        String clientId,
        String clientSecretHash,
        String clientName,
        String redirectUrisRaw,
        String grantTypesRaw,
        String scopesRaw,
        boolean requirePkce,
        boolean publicClient,
        boolean active,
        /** Bound tenant for service accounts; null otherwise — 服务账号绑定租户；否则为空。 */
        String tenantId,
        /** First-party (console) skips consent — 一等（控制台）跳过同意。 */
        boolean firstParty) {

    public List<String> redirectUris() {
        return split(redirectUrisRaw);
    }

    public List<String> grantTypes() {
        return split(grantTypesRaw);
    }

    public List<String> scopes() {
        return split(scopesRaw);
    }

    public boolean allowsRedirect(String redirectUri) {
        if (redirectUri == null || redirectUri.isBlank()) {
            return false;
        }
        return redirectUris().stream().anyMatch(u -> u.equals(redirectUri.trim()));
    }

    public boolean allowsGrant(String grantType) {
        return grantType != null && grantTypes().contains(grantType);
    }

    /** True when client_credentials + tenant bound — 是否为租户绑定的服务账号客户端。 */
    public boolean serviceAccount() {
        return !publicClient && tenantId != null && !tenantId.isBlank() && allowsGrant("client_credentials");
    }

    /** Third-party Auth Code client that needs consent — 需同意的第三方授权码客户端。 */
    public boolean requiresConsent() {
        return !firstParty && allowsGrant("authorization_code");
    }

    static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.trim().split("[\\s,]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
