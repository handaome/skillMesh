package com.skillmesh.api.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByOidcIssuerAndOidcSubject(String oidcIssuer, String oidcSubject);

    /** 内置账号按用户名找。登录不区分大小写。 */
    Optional<UserEntity> findByUsernameIgnoreCase(String username);

    /** 内置账号有几个。用来判断「第一个内置账号」是谁。 */
    long countByUsernameNotNull();
}
