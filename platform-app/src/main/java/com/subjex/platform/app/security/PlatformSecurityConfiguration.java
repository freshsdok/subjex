package com.subjex.platform.app.security;

import com.subjex.platform.contract.tenant.TenantGuard;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * PlatformSecurityConfiguration — 平台安全：先认出操作员，再按具名权限放行每一个 HTTP 动作。
 * <p>
 * Anonymous access is limited to liveness/readiness probes and auth token endpoints (login/refresh/logout).
 * {@code Authorization: Bearer} (opaque platform tokens) is the primary path for the console; HTTP Basic
 * remains for scripts and local tooling. {@link JdbcOperatorDirectory} backs Basic; {@link JdbcOperatorTokenStore}
 * backs Bearer. CSRF is off because there is no browser form posting to Java.
 * 匿名访问只留给探针与令牌端点。控制台主路径是 Bearer；Basic 留给脚本。无浏览器表单直投 Java，关闭 CSRF。
 */
@Configuration
public class PlatformSecurityConfiguration {

    @Bean
    SecurityFilterChain platformSecurity(HttpSecurity http, JdbcOperatorTokenStore tokenStore) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        // Token issue / rotate / revoke — no prior auth (password or refresh in body).
                        // 签发 / 轮换 / 吊销 — 事先无需认证（口令或刷新令牌在正文里）。
                        .requestMatchers(HttpMethod.POST,
                                OperatorAuthEndpoint.PATH + "/login",
                                OperatorAuthEndpoint.PATH + "/refresh",
                                OperatorAuthEndpoint.PATH + "/logout")
                                .permitAll()
                        // JSON twins under /api/v1: same named permissions as their pages.
                        // /api/v1 下的 JSON 孪生接口：与对应页面相同的具名权限。
                        .requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated()
                        // Self password change: any signed-in operator (current password checked in endpoint).
                        // 自己改密：已登录即可（端点内核对当前口令）。
                        .requestMatchers(HttpMethod.POST, "/api/v1/operators/me/password").authenticated()
                        .requestMatchers("/api/v1/operators", "/api/v1/operators/**")
                                .hasAuthority(OperatorPermission.OPERATOR_MANAGE.permissionName())
                        // Tenant list/get: admin.read; writes: tenant.manage.
                        // 租户列表/读取：admin.read；写操作：tenant.manage。
                        .requestMatchers(HttpMethod.GET, "/api/v1/tenants", "/api/v1/tenants/**")
                                .hasAuthority(OperatorPermission.ADMIN_READ.permissionName())
                        .requestMatchers("/api/v1/tenants", "/api/v1/tenants/**")
                                .hasAuthority(OperatorPermission.TENANT_MANAGE.permissionName())
                        .requestMatchers("/api/v1/openapi.json", "/api/v1/openapi.json/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/services")
                                .hasAuthority(OperatorPermission.REGISTRY_READ.permissionName())
                        .requestMatchers(HttpMethod.GET, "/api/v1/config")
                                .hasAuthority(OperatorPermission.CONFIG_READ.permissionName())
                        .requestMatchers(HttpMethod.PUT, "/api/v1/config/**")
                                .hasAuthority(OperatorPermission.CONFIG_WRITE.permissionName())
                        // Form/page detail + submissions: signed-in only; declared permission checked in the endpoint.
                        // 表单/页面详情与提交：只要求已登录；声明上的权限在接口里核对（免改安全配置）。
                        .requestMatchers(HttpMethod.POST, "/api/v1/forms/*/submissions")
                                .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/forms/*/submissions")
                                .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/forms/*")
                                .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/pages/*")
                                .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit")
                                .hasAuthority(OperatorPermission.ADMIN_READ.permissionName())
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/deploy", "/api/v1/forms", "/api/v1/pages", "/api/v1/codegen", "/api/v1/language", "/api/v1/skins")
                                .hasAuthority(OperatorPermission.PAGE_READ.permissionName())
                        .requestMatchers("/admin", "/admin/**", "/audit")
                                .hasAuthority(OperatorPermission.ADMIN_READ.permissionName())
                        .requestMatchers(HttpMethod.POST, "/config/entries")
                                .hasAuthority(OperatorPermission.CONFIG_WRITE.permissionName())
                        .requestMatchers("/config", "/config/**")
                                .hasAuthority(OperatorPermission.CONFIG_READ.permissionName())
                        .requestMatchers(HttpMethod.POST, "/registry/services")
                                .hasAuthority(OperatorPermission.REGISTRY_WRITE.permissionName())
                        .requestMatchers("/registry/**", "/services")
                                .hasAuthority(OperatorPermission.REGISTRY_READ.permissionName())
                        .requestMatchers("/tasks", "/tasks/**")
                                .hasAuthority(OperatorPermission.TASK_WRITE.permissionName())
                        .requestMatchers("/deploy", "/forms", "/codegen", "/language", "/skin")
                                .hasAuthority(OperatorPermission.PAGE_READ.permissionName())
                        // Unmapped paths still need a signed-in operator; they answer 404, not data.
                        // 未映射的路径仍要求已登录的操作员；它们只会回 404，不给数据。
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .addFilterBefore(new BearerTokenAuthenticationFilter(tokenStore), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * Hashes carry their encoder id, e.g. {@code {bcrypt}} — 摘要带编码器标识，例如 {@code {bcrypt}}。
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @Order(SecurityProperties.DEFAULT_FILTER_ORDER + 10)
    TenantEnforcementFilter tenantEnforcementFilter(TenantGuard tenantGuard, OperatorTenantAccess tenantAccess) {
        return new TenantEnforcementFilter(tenantGuard, tenantAccess);
    }
}
