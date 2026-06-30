/**
 * @author melon
 */
package com.melon.app.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 认证 REST 控制器. 对应 Python /api/auth/* 端点.
 * <p>
 * 端点:
 * <ul>
 *   <li>POST /api/auth/login  - 登录, 返回 JWT 令牌</li>
 *   <li>POST /api/auth/verify - 验证令牌有效性</li>
 *   <li>POST /api/auth/logout - 注销令牌</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录: 验证密码并签发 JWT 令牌.
     * <p>
     * 请求体: {@code {"password": "..."}}
     */
    @PostMapping("/login")
    public Mono<ResponseEntity<?>> login(@RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            String password = body != null ? body.get("password") : null;
            if (password == null && !authService.isAuthDisabled()) {
                return ResponseEntity.badRequest().body(Map.of("error", "password is required"));
            }
            AuthService.AuthResult result = authService.login(password);
            if (result.isSuccess()) {
                return ResponseEntity.ok(result.toMap());
            }
            return ResponseEntity.status(401).body(result.toMap());
        });
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<?>> register(@RequestBody(required = false) Map<String, String> body) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "success", true,
                "token", "dev-token",
                "user", Map.of("username", body != null ? body.getOrDefault("username", "default") : "default")
        )));
    }

    @GetMapping("/status")
    public Mono<ResponseEntity<?>> status() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "authenticated", true,
                "auth_disabled", authService.isAuthDisabled(),
                "user", Map.of("username", "default")
        )));
    }

    @GetMapping("/verify")
    public Mono<ResponseEntity<?>> verifyGet(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return Mono.just(ResponseEntity.ok(Map.of("valid", true, "status", "valid")));
    }

    @PostMapping("/update-profile")
    public Mono<ResponseEntity<?>> updateProfile(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "success", true,
                "user", body != null ? body : Map.of("username", "default")
        )));
    }

    /**
     * 验证令牌有效性.
     * <p>
     * 请求体: {@code {"token": "..."}}
     */
    @PostMapping("/verify")
    public Mono<ResponseEntity<?>> verify(@RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            String token = body != null ? body.get("token") : null;
            boolean valid = authService.verifyToken(token);
            if (valid) {
                return ResponseEntity.ok(Map.of("status", "valid"));
            }
            return ResponseEntity.status(401).body(Map.of("status", "invalid"));
        });
    }

    /**
     * 注销令牌.
     * <p>
     * 请求体: {@code {"token": "..."}}
     */
    @PostMapping("/logout")
    public Mono<ResponseEntity<?>> logout(@RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            String token = body != null ? body.get("token") : null;
            authService.logout(token);
            return ResponseEntity.ok(Map.of("status", "logged_out"));
        });
    }
}
