package com.skillmesh.api.config;

import com.skillmesh.api.auth.TokenKeys;
import com.skillmesh.api.auth.TokenService;
import com.skillmesh.api.common.RateLimitFilter;
import com.skillmesh.api.common.RequestLogFilter;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
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
                // 内置账号的注册、登录、刷新：还没登录时就得能用。
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(accessTokenOnly())))
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
     * 刷新令牌不能当访问令牌用。
     *
     * 本地令牌带 typ 标记：access 放行、refresh 拦下。
     * 外部 IdP 的令牌没有这个标记，一律放行——那边怎么发的是它的事。
     */
    private static Converter<Jwt, ? extends AbstractAuthenticationToken> accessTokenOnly() {
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        return jwt -> {
            if (TokenService.TYPE_REFRESH.equals(jwt.getClaimAsString("typ"))) {
                throw new BadCredentialsException("这是刷新令牌，不能当访问令牌用");
            }
            return delegate.convert(jwt);
        };
    }

    /**
     * 令牌解码器：本地自签与外部 IdP 两个来源。
     *
     * 自己定义而不用 Spring Boot 的自动配置，是因为那个用
     * {@code @ConditionalOnProperty} 判断——<b>空字符串也算「配了」</b>，
     * 于是 issuer-uri 与 jwk-set-uri 只要都出现在配置里（哪怕一个是空串），
     * 就会注册出两个 JwtDecoder，应用直接起不来。
     *
     * 没配外部 IdP 时只认本地令牌（HS256 自签），这样不装 Keycloak 也能跑。
     * 配了外部 IdP 就两条路都认：先试本地，签名对不上再交给外部解码器。
     *
     * 给了 jwk-set-uri 就只验签名、不校验令牌里的 iss——容器内外 issuer 天然不一致
     * （容器里的 127.0.0.1 指自己），这是 compose 部署那条路的取舍。
     * 否则按 issuer-uri 拉元数据并校验 iss（本机与生产都走这条）。
     */
    @Bean
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties, TokenKeys keys) {
        NimbusJwtDecoder local = NimbusJwtDecoder.withSecretKey(keys.secretKey())
            .macAlgorithm(MacAlgorithm.HS256).build();
        String jwkSetUri = properties.getJwt().getJwkSetUri();
        String issuerUri = properties.getJwt().getIssuerUri();
        boolean hasJwkSet = jwkSetUri != null && !jwkSetUri.isBlank();
        boolean hasIssuer = issuerUri != null && !issuerUri.isBlank();
        if (!hasJwkSet && !hasIssuer) {
            return local;
        }
        JwtDecoder remote = new LazyRemoteDecoder(jwkSetUri, issuerUri);
        return token -> {
            try {
                return local.decode(token);
            } catch (JwtException e) {
                return remote.decode(token);
            }
        };
    }

    /**
     * 外部 IdP 的解码器：等第一次真用上它时才去解析。
     *
     * 之前是启动时就把 issuer 解析掉：IdP 还没起来（或者干脆没部署），
     * 整个服务直接起不来——内置账号那条路也跟着一起废了。
     * 现在解析挪到第一次解码时，IdP 不可用只会让那一次请求 401。
     */
    private static final class LazyRemoteDecoder implements JwtDecoder {

        private final String jwkSetUri;
        private final String issuerUri;
        private final AtomicReference<JwtDecoder> delegate = new AtomicReference<>();

        LazyRemoteDecoder(String jwkSetUri, String issuerUri) {
            this.jwkSetUri = jwkSetUri;
            this.issuerUri = issuerUri;
        }

        @Override
        public Jwt decode(String token) throws JwtException {
            return delegate().decode(token);
        }

        private JwtDecoder delegate() {
            JwtDecoder current = delegate.get();
            if (current != null) {
                return current;
            }
            synchronized (this) {
                current = delegate.get();
                if (current == null) {
                    current = resolve();
                    delegate.set(current);
                }
                return current;
            }
        }

        private JwtDecoder resolve() {
            try {
                boolean hasJwkSet = jwkSetUri != null && !jwkSetUri.isBlank();
                return hasJwkSet
                    ? NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
                    : JwtDecoders.fromIssuerLocation(issuerUri);
            } catch (RuntimeException e) {
                // 解析失败不是令牌的问题，但对外只能表现为「这个令牌不能用」，
                // 不能把 IdP 的连接错误原样抛给调用方。
                throw new JwtException("外部 IdP 的解码器不可用: " + e.getMessage(), e);
            }
        }
    }
}
