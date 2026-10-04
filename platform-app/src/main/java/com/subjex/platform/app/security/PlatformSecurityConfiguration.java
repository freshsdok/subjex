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
import org.springframework.security.web.SecurityFilterChain;

/**
 * PlatformSecurityConfiguration — 平台安全：默认要求操作员认证。
 * <p>
 * Anonymous access is limited to liveness and readiness probes. There is no browser form.
 * HTTP Basic is the operator gate. CSRF is off because v1 has no online form.
 * 匿名访问只留给存活和就绪探针。没有浏览器表单。
 * HTTP Basic 是操作员门禁。第一版没有在线表单，因此关闭 CSRF。
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
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    @Order(SecurityProperties.DEFAULT_FILTER_ORDER + 10)
    TenantEnforcementFilter tenantEnforcementFilter(TenantGuard tenantGuard) {
        return new TenantEnforcementFilter(tenantGuard);
    }
}
