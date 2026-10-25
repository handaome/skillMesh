package com.skillmesh.api.config;

import com.skillmesh.api.common.RateLimitFilter;
import com.skillmesh.api.common.RequestLogFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    @Value("${skillmesh.rate-limit.read-per-minute:600}") int readLimit,
                                    @Value("${skillmesh.rate-limit.write-per-minute:60}") int writeLimit,
                                    @Value("${skillmesh.rate-limit.window-seconds:60}") long windowSeconds)
            throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // 邀请摘要页要给还没登录的人看。* 不跨 /，所以 /invitations/{token}/accept 仍需登录。
                .requestMatchers(HttpMethod.GET, "/api/v1/invitations/*").permitAll()
                // 网页控制台登录前要知道 issuer 与 client_id，不放行就 401。
                .requestMatchers(HttpMethod.GET, "/api/v1/config/public").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
            // 限流挂在令牌解析之后、授权之前：认得出人的按人计数，认不出的按来源 IP 计数。
            // 挂在链外当普通 servlet 过滤器的话，未鉴权的请求被 401 挡在限流之前，刷量不消耗额度。
            // 这里 new 而不是注入 bean：任何 Filter bean 都会被 Spring Boot 自动注册成
            // servlet 过滤器，那样同一个请求会被数两遍。
            .addFilterAfter(new RateLimitFilter(readLimit, writeLimit, windowSeconds),
                BearerTokenAuthenticationFilter.class)
            // 请求日志挂在最前：所有请求都要记，包括被 401 挡下的——
            // 「谁在试」正是日志要回答的问题（设计文档 §13）。
            .addFilterBefore(new RequestLogFilter(), SecurityContextHolderFilter.class)
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
