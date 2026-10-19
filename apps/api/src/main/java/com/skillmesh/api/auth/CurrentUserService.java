package com.skillmesh.api.auth;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 从 OIDC token 解析当前用户，首次访问时自动建档。 */
@Service
public class CurrentUserService {

    private final UserRepository users;

    public CurrentUserService(UserRepository users) {
        this.users = users;
    }

    @Transactional
    public CurrentUser current(Jwt jwt) {
        String issuer = jwt.getIssuer().toString();
        String subject = jwt.getSubject();
        UserEntity user = users.findByOidcIssuerAndOidcSubject(issuer, subject)
            .orElseGet(() -> provision(issuer, subject, jwt));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        user.setLastLoginAt(now);
        user.setUpdatedAt(now);
        users.save(user);
        return new CurrentUser(user.getId(), subject, user.getDisplayName(), user.getEmail());
    }

    private UserEntity provision(String issuer, String subject, Jwt jwt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setOidcIssuer(issuer);
        user.setOidcSubject(subject);
        user.setDisplayName(displayName(jwt));
        user.setEmail(jwt.getClaimAsString("email"));
        user.setStatus("active");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return users.save(user);
    }

    private static String displayName(Jwt jwt) {
        for (String claim : List.of("name", "preferred_username")) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return jwt.getSubject();
    }
}
