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

/**
 * PlatformSecurityConfiguration — 平台安全：先认出操作员，再按具名权限放行每一个 HTTP 动作。
 * <p>
 * Anonymous access is limited to liveness and readiness probes. HTTP Basic identifies the operator through
 * {@link JdbcOperatorDirectory}; each path then needs one {@link OperatorPermission}. A signed-in operator without
 * that permission gets 403. CSRF is off because there is no browser form.
 * 匿名访问只留给存活和就绪探针。HTTP Basic 经 {@link JdbcOperatorDirectory} 认出操作员，然后每条路径需要一项
 * {@link OperatorPermission}。已登录但没有这项权限的操作员得到 403。没有浏览器表单，因此关闭 CSRF。
 */
@Configuration
public class PlatformSecurityConfiguration {

    @Bean
    SecurityFilterChain platformSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
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
    TenantEnforcementFilter tenantEnforcementFilter(TenantGuard tenantGuard) {
        return new TenantEnforcementFilter(tenantGuard);
    }
}
