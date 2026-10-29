package com.skillmesh.api.auth;

import com.skillmesh.api.auth.dto.AuthResponse;
import com.skillmesh.api.auth.dto.LoginRequest;
import com.skillmesh.api.auth.dto.RefreshRequest;
import com.skillmesh.api.auth.dto.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内置账号的注册、登录与刷新。
 *
 * 三个接口都免鉴权：注册时还没账号，登录更是。
 * 走外部 IdP 的老流程不受影响，两套并存。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final LocalAuthService auth;

    public AuthController(LocalAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return respond(auth.register(request.username(), request.password(),
            request.displayName(), request.email()));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return respond(auth.login(request.username(), request.password()));
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return respond(auth.refresh(request.refreshToken()));
    }

    private static AuthResponse respond(TokenService.AuthTokens tokens) {
        return new AuthResponse(tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn(), tokens.username());
    }
}
