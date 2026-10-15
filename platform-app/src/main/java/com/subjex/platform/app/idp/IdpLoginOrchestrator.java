package com.subjex.platform.app.idp;

import com.subjex.platform.app.security.InvalidOperatorTokenException;
import com.subjex.platform.app.security.JdbcOperatorDirectory;
import com.subjex.platform.app.security.JdbcOperatorIdpLinkStore;
import com.subjex.platform.app.security.JdbcOperatorTokenStore;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.util.Objects;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * IdpLoginOrchestrator — adapter callback → link lookup → platform tokens (ID-4).
 * Never auto-provisions operators (fail-closed when unlinked).
 * 适配器回调 → 查绑定 → 平台令牌；未绑定绝不建号。
 */
public final class IdpLoginOrchestrator {

    private final IdpAdapterRegistry registry;
    private final JdbcOperatorIdpLinkStore linkStore;
    private final JdbcOperatorDirectory directory;
    private final JdbcOperatorTokenStore tokenStore;

    public IdpLoginOrchestrator(
            IdpAdapterRegistry registry,
            JdbcOperatorIdpLinkStore linkStore,
            JdbcOperatorDirectory directory,
            JdbcOperatorTokenStore tokenStore) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.linkStore = Objects.requireNonNull(linkStore, "linkStore");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
    }

    public IdpAuthStart start(String providerId) {
        IdpAdapter adapter = requireEnabled(providerId);
        return adapter.startAuth();
    }

    public LoginResult complete(String providerId, IdpCallbackRequest request, String userAgent, String clientIp) {
        IdpAdapter adapter = requireEnabled(providerId);
        IdpExternalIdentity identity = adapter.handleCallback(request);
        JdbcOperatorIdpLinkStore.LinkedOperator link = linkStore
                .findByIssuerAndSubject(identity.issuer(), identity.subject())
                .orElseThrow(() -> new IdpUnlinkedException(
                        identity.providerId(), identity.issuer(), identity.subject(), identity.email()));
        OperatorPrincipal operator;
        try {
            operator = (OperatorPrincipal) directory.loadUserByUsername(link.loginName());
        } catch (UsernameNotFoundException ex) {
            throw new IdpUnlinkedException(
                    identity.providerId(), identity.issuer(), identity.subject(), identity.email());
        }
        if (!operator.isEnabled()) {
            throw new InvalidOperatorTokenException("operator-disabled");
        }
        JdbcOperatorTokenStore.IssuedTokens issued = tokenStore.issue(operator, userAgent, clientIp);
        return new LoginResult(operator, issued, identity);
    }

    private IdpAdapter requireEnabled(String providerId) {
        IdpAdapter adapter = registry
                .find(providerId)
                .orElseThrow(() -> new IllegalArgumentException("idp-unknown"));
        if (!adapter.enabled()) {
            throw new IllegalStateException("idp-disabled");
        }
        return adapter;
    }

    public record LoginResult(
            OperatorPrincipal operator,
            JdbcOperatorTokenStore.IssuedTokens tokens,
            IdpExternalIdentity identity) {}
}
