package com.subjex.sample.consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * ConsumerSecurityConfiguration — 消费者安全：默认需要操作员认证，探针除外。
 * <p>
 * HTTP stays authenticated except for probes. The outbox socket is not an HTTP route; the listener checks the operator.
 * 除探针外，HTTP 需要认证。出箱套接字不是 HTTP 路由；监听者自行核对操作员。
 */
@Configuration
public class ConsumerSecurityConfiguration {

    @Bean
    SecurityFilterChain consumerSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
