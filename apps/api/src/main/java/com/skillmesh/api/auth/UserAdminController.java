package com.skillmesh.api.auth;

import com.skillmesh.api.auth.dto.RegisterRequest;
import com.skillmesh.api.auth.dto.UserAccount;
import com.skillmesh.api.auth.dto.UserStatusRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点管理员的账号管理：建号与账号列表。
 *
 * 自助注册关掉后，账号只能从这里来。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserAdminController {

    private final UserAdminService users;
    private final CurrentUserService currentUserService;

    public UserAdminController(UserAdminService users, CurrentUserService currentUserService) {
        this.users = users;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    public UserAccount create(@Valid @RequestBody RegisterRequest request,
                              @AuthenticationPrincipal Jwt jwt) {
        return users.create(currentUserService.current(jwt), request.username(), request.password(),
            request.displayName(), request.email());
    }

    @GetMapping
    public List<UserAccount> list(@AuthenticationPrincipal Jwt jwt) {
        return users.list(currentUserService.current(jwt));
    }

    @PatchMapping("/{id}/status")
    public UserAccount setStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                  @RequestBody UserStatusRequest request) {
        return users.setStatus(currentUserService.current(jwt), id, request.status());
    }
}
