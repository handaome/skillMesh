package com.skillmesh.api.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * 内置账号的令牌签发与校验。
 *
 * 令牌是自签的 JWT（HS256），不依赖任何外部 IdP：
 * 不接外部 IdP 也能登录。外部 IdP 登录的令牌仍按原来的路子验签，
 * 两边的令牌在解码器里分流（见 SecurityConfig）。
 */
@Component
public class TokenService {

    /** 本地令牌的签发方标记，与外部 IdP 的 issuer 区分开。 */
    public static final String ISSUER = "skillmesh-local";

    /** 令牌类型：访问令牌与刷新令牌分开，刷新令牌不能当访问令牌用。 */
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public TokenService(TokenKeys keys,
                        JwtDecoder decoder,
                        @Value("${skillmesh.auth.access-token-minutes:60}") long accessMinutes,
                        @Value("${skillmesh.auth.refresh-token-days:30}") long refreshDays) {
        // HMAC 签发：把密钥包成 JWK source，NimbusJwtEncoder 据此签 HS256。
        JWKSource<SecurityContext> jwkSource = new ImmutableSecret<>(keys.secretKey());
        this.encoder = new NimbusJwtEncoder(jwkSource);
        this.decoder = decoder;
        this.accessTtl = Duration.ofMinutes(accessMinutes);
        this.refreshTtl = Duration.ofDays(refreshDays);
    }

    /** 签发一对令牌：访问令牌短期有效，刷新令牌长期有效。 */
    public AuthTokens issue(UserEntity user) {
        Instant now = Instant.now();
        String access = encode(user, TYPE_ACCESS, now, now.plus(accessTtl));
        String refresh = encode(user, TYPE_REFRESH, now, now.plus(refreshTtl));
        return new AuthTokens(access, refresh, accessTtl.toSeconds(), user.getUsername());
    }

    /** 校验刷新令牌：必须是刷新令牌，且签名与有效期都得过。 */
    public Jwt verifyRefresh(String token) {
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException e) {
            throw new ApiException(401, ErrorCodes.INVALID_CREDENTIALS, "刷新令牌无效或已过期");
        }
        if (!TYPE_REFRESH.equals(jwt.getClaimAsString("typ"))) {
            throw new ApiException(401, ErrorCodes.INVALID_CREDENTIALS, "这不是刷新令牌");
        }
        return jwt;
    }

    private String encode(UserEntity user, String type, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(user.getUsername())
            .id(UUID.randomUUID().toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("uid", user.getId().toString())
            .claim("typ", type)
            .claim("name", user.getDisplayName())
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** 签发结果。expiresIn 是访问令牌的剩余秒数。 */
    public record AuthTokens(String accessToken, String refreshToken, long expiresIn, String username) {}
}
