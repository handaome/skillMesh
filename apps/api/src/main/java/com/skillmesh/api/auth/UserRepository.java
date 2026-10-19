package com.skillmesh.api.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByOidcIssuerAndOidcSubject(String oidcIssuer, String oidcSubject);
}
