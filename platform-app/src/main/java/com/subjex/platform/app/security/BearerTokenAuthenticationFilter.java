package com.subjex.platform.app.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.subjex.platform.app.identity.OpJwtService;
import com.subjex.platform.app.identity.ScopePermissionBridge;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * BearerTokenAuthenticationFilter — opaque platform tokens, then OP JWT (human or SA) (ID-1/ID-2).
 * <p>
 * Order: opaque {@link JdbcOperatorTokenStore}; else RS256 access JWT — human via
 * {@code preferred_username}, service account via {@code client_id}+{@code scope}+{@code tenant_id}.
 * 先不透明令牌；再 OP JWT：人工走 preferred_username，服务账号走 client_id/scope/tenant_id。
 */
public final class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String BEARER_PREFIX = "Bearer ";

    private final JdbcOperatorTokenStore tokenStore;
    private final OpJwtService jwtService;
    private final JdbcOperatorDirectory directory;

    public BearerTokenAuthenticationFilter(JdbcOperatorTokenStore tokenStore) {
        this(tokenStore, null, null);
    }

    public BearerTokenAuthenticationFilter(
            JdbcOperatorTokenStore tokenStore, OpJwtService jwtService, JdbcOperatorDirectory directory) {
        this.tokenStore = tokenStore;
        this.jwtService = jwtService;
        this.directory = directory;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String raw = header.substring(BEARER_PREFIX.length()).trim();
            Optional<OperatorPrincipal> principal = tokenStore.resolveAccessToken(raw);
            if (principal.isEmpty()) {
                principal = resolveJwt(raw);
            }
            if (principal.isPresent()) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        principal.get(), null, principal.get().getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }

    private Optional<OperatorPrincipal> resolveJwt(String raw) {
        if (jwtService == null) {
            return Optional.empty();
        }
        Optional<JWTClaimsSet> claimsOpt = jwtService.verifyAccessToken(raw);
        if (claimsOpt.isEmpty()) {
            return Optional.empty();
        }
        JWTClaimsSet claims = claimsOpt.get();
        Object login = claims.getClaim(OpJwtService.CLAIM_LOGIN);
        if (login != null && !String.valueOf(login).isBlank()) {
            if (directory == null) {
                return Optional.empty();
            }
            try {
                return Optional.of((OperatorPrincipal) directory.loadUserByUsername(String.valueOf(login)));
            } catch (UsernameNotFoundException | ClassCastException ex) {
                return Optional.empty();
            }
        }
        // ID-2 service account: client_id + tenant_id + scope → synthetic OperatorPrincipal.
        // 服务账号：client_id + tenant_id + scope → 合成 OperatorPrincipal。
        Object clientId = claims.getClaim(OpJwtService.CLAIM_CLIENT_ID);
        Object tenantId = claims.getClaim(OpJwtService.CLAIM_TENANT_ID);
        if (clientId == null || String.valueOf(clientId).isBlank()) {
            return Optional.empty();
        }
        if (tenantId == null || String.valueOf(tenantId).isBlank()) {
            return Optional.empty();
        }
        Object scope = claims.getClaim(OpJwtService.CLAIM_SCOPE);
        Set<String> permissions = ScopePermissionBridge.toPermissions(scope == null ? "" : String.valueOf(scope));
        return Optional.of(OperatorPrincipal.serviceAccount(
                String.valueOf(clientId), String.valueOf(tenantId), permissions));
    }
}
