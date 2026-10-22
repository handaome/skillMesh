package com.skillmesh.api.config;

import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // 邀请摘要页要给还没登录的人看。* 不跨 /，所以 /invitations/{token}/accept 仍需登录。
                .requestMatchers(HttpMethod.GET, "/api/v1/invitations/*").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
            .build();
    }

    /**
     * 令牌解码器：两个来源二选一。
     *
     * 自己定义而不用 Spring Boot 的自动配置，是因为那个用
     * {@code @ConditionalOnProperty} 判断——<b>空字符串也算「配了」</b>，
     * 于是 issuer-uri 与 jwk-set-uri 只要都出现在配置里（哪怕一个是空串），
     * 就会注册出两个 JwtDecoder，应用直接起不来。
     *
     * 给了 jwk-set-uri 就只验签名、不校验令牌里的 iss——容器内外 issuer 天然不一致
     * （容器里的 127.0.0.1 指自己），这是 compose 部署那条路的取舍。
     * 否则按 issuer-uri 拉元数据并校验 iss（本机与生产都走这条）。
     */
    @Bean
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
        String jwkSetUri = properties.getJwt().getJwkSetUri();
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        }
        String issuerUri = properties.getJwt().getIssuerUri();
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException(
                "既没有配 spring.security.oauth2.resourceserver.jwt.issuer-uri，"
                    + "也没有配 jwk-set-uri，令牌没法验");
        }
        return JwtDecoders.fromIssuerLocation(issuerUri);
    }
}
