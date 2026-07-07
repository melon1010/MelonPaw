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
                return ResponseEntity.badRequest().body(Map.of("error", "password is required", "detail", "password is required"));
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
        return Mono.fromCallable(() -> {
            if (authService.isAuthDisabled()) {
                return ResponseEntity.ok(authService.login(null).toMap());
            }
            return ResponseEntity.status(409).body(Map.of(
                    "status", "error",
                    "detail", "Registration is not supported by env-based auth"
            ));
        });
    }

    @GetMapping("/status")
    public Mono<ResponseEntity<?>> status() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "enabled", !authService.isAuthDisabled(),
                "has_users", authService.isAuthDisabled() || authService.hasConfiguredPassword(),
                "authenticated", authService.isAuthDisabled(),
                "auth_disabled", authService.isAuthDisabled(),
                "user", Map.of("username", authService.isAuthDisabled() ? "auth_disabled" : "melon")
        )));
    }

    @GetMapping("/verify")
    public Mono<ResponseEntity<?>> verifyGet(@RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean valid = authService.isAuthDisabled() || authService.verifyToken(bearerToken(authorization));
        return Mono.just(valid
                ? ResponseEntity.ok(Map.of("valid", true, "status", "valid"))
                : ResponseEntity.status(401).body(Map.of("valid", false, "status", "invalid")));
    }

    @PostMapping("/update-profile")
    public Mono<ResponseEntity<?>> updateProfile(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String token = bearerToken(authorization);
            if (!authService.isAuthDisabled() && !authService.verifyToken(token)) {
                return ResponseEntity.status(401).body(Map.of("detail", "Invalid token"));
            }
            String currentPassword = stringValue(body != null ? body.get("current_password") : null);
            if (!authService.checkPassword(currentPassword)) {
                return ResponseEntity.status(401).body(Map.of("detail", "Incorrect password"));
            }
            if (!stringValue(body != null ? body.get("new_username") : null).isBlank()
                    || !stringValue(body != null ? body.get("new_password") : null).isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("detail", "Profile updates require env configuration changes"));
            }
            return ResponseEntity.ok(Map.of("token", token, "username", authService.isAuthDisabled() ? "auth_disabled" : "melon"));
        });
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
            boolean valid = authService.isAuthDisabled() || authService.verifyToken(token);
            if (valid) {
                return ResponseEntity.ok(Map.of("valid", true, "status", "valid"));
            }
            return ResponseEntity.status(401).body(Map.of("valid", false, "status", "invalid"));
        });
    }

    /**
     * 注销令牌.
     * <p>
     * 请求体: {@code {"token": "..."}}
     */
    @PostMapping("/logout")
    public Mono<ResponseEntity<?>> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody(required = false) Map<String, String> body) {
        return Mono.fromCallable(() -> {
            String token = body != null ? body.get("token") : null;
            if (token == null || token.isBlank()) {
                token = bearerToken(authorization);
            }
            authService.logout(token);
            return ResponseEntity.ok(Map.of("status", "logged_out"));
        });
    }

    private String bearerToken(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return value;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
