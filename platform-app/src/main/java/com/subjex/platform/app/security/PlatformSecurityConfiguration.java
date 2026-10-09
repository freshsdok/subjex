package com.subjex.platform.app.security;

import com.subjex.platform.contract.tenant.TenantGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
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
 * Anonymous access is limited to liveness/readiness probes and auth token endpoints (login/refresh/logout/mfa-verify/oidc).
 * {@code Authorization: Bearer} (opaque platform tokens) is the primary path for the console; HTTP Basic
 * is off by default ({@code platform.auth.http-basic-enabled=false}) and on under {@code local}
 * (or {@code PLATFORM_AUTH_HTTP_BASIC_ENABLED=true} for scripts in shared envs).
 * {@link JdbcOperatorDirectory} backs Basic when enabled; {@link JdbcOperatorTokenStore} backs Bearer.
 * CSRF is off because there is no browser form posting to Java.
 * 匿名访问只留给探针与令牌端点（含 OIDC）。控制台主路径是 Bearer；HTTP Basic 默认关，{@code local} 打开（共享环境脚本可设环境变量）。无浏览器表单直投 Java，关闭 CSRF。
 */
@Configuration
public class PlatformSecurityConfiguration {

    @Bean
    SecurityFilterChain platformSecurity(
            HttpSecurity http,
            JdbcOperatorTokenStore tokenStore,
            @Value("${platform.auth.http-basic-enabled:false}") boolean httpBasicEnabled)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Without httpBasic/formLogin, Spring defaults to 403 for anonymous; keep 401 for API clients.
                // 未开 httpBasic/formLogin 时 Spring 对匿名默认 403；API 客户端统一回 401。
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // Health may appear on the business port only if management.server.port is unset; prometheus is on the management port (P2).
                        // 未设 management.server.port 时探针可能仍在业务口；prometheus 走管理端口（P2）。
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        // Token issue / rotate / revoke — no prior auth (password or refresh in body).
                        // 签发 / 轮换 / 吊销 — 事先无需认证（口令或刷新令牌在正文里）。
                        .requestMatchers(HttpMethod.POST,
                                OperatorAuthEndpoint.PATH + "/login",
                                OperatorAuthEndpoint.PATH + "/refresh",
                                OperatorAuthEndpoint.PATH + "/logout",
                                OperatorAuthEndpoint.PATH + "/mfa/verify",
                                OperatorOidcEndpoint.PATH + "/start",
                                OperatorOidcEndpoint.PATH + "/callback")
                                .permitAll()
                        .requestMatchers(HttpMethod.GET, OperatorOidcEndpoint.PATH + "/status")
                                .permitAll()
                        // MFA enroll/disable/status — signed-in operator only.
                        // MFA 登记/关闭/状态 — 仅已登录操作员。
                        .requestMatchers(OperatorMfaEndpoint.PATH, OperatorMfaEndpoint.PATH + "/**")
                                .authenticated()
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
                        // Entity list (service-note /notes shim + generic GET); same page.read as the console pages index.
                        // 实体列表（service-note /notes 垫片 + 通用 GET）；与控制台页面目录同为 page.read。
                        .requestMatchers(HttpMethod.GET, "/api/v1/org/**")
                                .hasAuthority(OperatorPermission.ORG_READ.permissionName())
                        .requestMatchers("/api/v1/org/**")
                                .hasAuthority(OperatorPermission.ORG_WRITE.permissionName())
                        // Declaration promote: POST needs declaration.promote; GET history uses declaration.read below.
                        // 声明晋升：POST 要 declaration.promote；GET 历史走下方 declaration.read。
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/declarations/*/*/promote",
                                "/api/v1/declarations/*/*/promote/approvals",
                                "/api/v1/declarations/*/*/rollback")
                                .hasAuthority(OperatorPermission.DECLARATION_PROMOTE.permissionName())
                        // Declaration migration queue: enqueue/review/apply need declaration.migrate; GET list uses declaration.read.
                        // 声明迁移队列：入队/审阅/执行要 declaration.migrate；GET 列表走 declaration.read。
                        .requestMatchers(HttpMethod.POST, "/api/v1/declarations/*/*/migrations",
                                "/api/v1/declarations/*/*/migrations/*/review",
                                "/api/v1/declarations/*/*/migrations/*/apply")
                                .hasAuthority(OperatorPermission.DECLARATION_MIGRATE.permissionName())
                        .requestMatchers(HttpMethod.GET, "/api/v1/declarations", "/api/v1/declarations/**")
                                .hasAuthority(OperatorPermission.DECLARATION_READ.permissionName())
                        .requestMatchers(HttpMethod.PUT, "/api/v1/declarations", "/api/v1/declarations/**")
                                .hasAuthority(OperatorPermission.DECLARATION_WRITE.permissionName())
                        .requestMatchers(HttpMethod.GET, "/api/v1/entities/**")
                                .hasAuthority(OperatorPermission.PAGE_READ.permissionName())
                        // Generic entity writes: signed-in only; declared permission checked in the endpoint.
                        // 通用实体写入：只要求已登录；声明权限在接口里核对。
                        .requestMatchers(HttpMethod.PUT, "/api/v1/entities/**")
                                .authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/entities/**")
                                .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/entities/**")
                                .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit")
                                .hasAuthority(OperatorPermission.ADMIN_READ.permissionName())
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/deploy", "/api/v1/forms", "/api/v1/pages", "/api/v1/codegen", "/api/v1/language", "/api/v1/skins",
                                "/api/v1/capabilities")
                                .hasAuthority(OperatorPermission.PAGE_READ.permissionName())
                        // Capability try-run: same page.read as list (stubs / preview only).
                        // 能力试跑：与目录相同 page.read（桩/预览）。
                        .requestMatchers(HttpMethod.POST, "/api/v1/capabilities/*/run")
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
                        .anyRequest().authenticated());
        if (httpBasicEnabled) {
            http.httpBasic(Customizer.withDefaults());
        } else {
            http.httpBasic(AbstractHttpConfigurer::disable);
        }
        return http
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
