package com.melon.app.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证服务. 对应 Python auth.py.
 * <p>
 * 单用户设计: 密码通过环境变量 {@code MELON_PASSWORD} 或 {@code MELON_PASSWORD_HASH} 配置.
 * 若两者均未配置, 则认证关闭 (开放模式, 仅供本地开发).
 * <p>
 * JWT 令牌使用 HMAC-SHA256 签名, 无外部依赖.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String ENV_PASSWORD = "MELON_PASSWORD";
    private static final String ENV_PASSWORD_HASH = "MELON_PASSWORD_HASH";
    private static final String ENV_JWT_SECRET = "MELON_JWT_SECRET";
    private static final String ENV_AUTH_DISABLED = "MELON_AUTH_DISABLED";

    private static final long TOKEN_TTL_SECONDS = 7 * 24 * 3600L; // 7 days
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HASH_ALGORITHM = "SHA-256";

    /** 已签发且未注销的令牌集合 (简单内存方案, 重启后失效). */
    private final Set<String> activeTokens = ConcurrentHashMap.newKeySet();

    private final String jwtSecret;
    private final String passwordHash;
    private final boolean authDisabled;

    public AuthService() {
        this.authDisabled = isAuthDisabled();
        this.passwordHash = resolvePasswordHash();
        this.jwtSecret = resolveJwtSecret();
        log.info("AuthService initialized: authDisabled={}, passwordConfigured={}", authDisabled, passwordHash != null);
    }

    // ======================== Public API ========================

    /**
     * 登录: 验证密码并签发 JWT 令牌.
     *
     * @param password 明文密码
     * @return 认证结果, 包含 token 或错误信息
     */
    public AuthResult login(String password) {
        if (authDisabled) {
            log.warn("Auth is disabled, granting token without password check");
            String token = generateToken();
            activeTokens.add(token);
            return AuthResult.success(token, "auth_disabled");
        }

        if (passwordHash == null) {
            log.error("No password configured but auth is enabled");
            return AuthResult.failure("Server auth not configured");
        }

        if (!verifyPassword(password, passwordHash)) {
            log.warn("Login failed: incorrect password");
            return AuthResult.failure("Invalid credentials");
        }

        String token = generateToken();
        activeTokens.add(token);
        log.info("Login successful, token issued");
        return AuthResult.success(token, "melon");
    }

    /**
     * 验证令牌有效性.
     *
     * @param token JWT 令牌
     * @return 令牌是否有效
     */
    public boolean verifyToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (authDisabled) {
            return true;
        }
        if (!activeTokens.contains(token)) {
            return false;
        }
        try {
            return validateJwt(token);
        } catch (Exception e) {
            log.warn("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 注销令牌.
     *
     * @param token JWT 令牌
     */
    public void logout(String token) {
        if (token != null) {
            activeTokens.remove(token);
            log.info("Token revoked");
        }
    }

    /**
     * 检查认证是否关闭.
     */
    public boolean isAuthDisabled() {
        String disabled = System.getenv(ENV_AUTH_DISABLED);
        return "true".equalsIgnoreCase(disabled) || "1".equals(disabled);
    }

    // ======================== Password Hashing (SHA-256) ========================

    /**
     * 使用 SHA-256 + 随机盐对密码进行哈希.
     * 格式: {@code salt$hash} (均为 Base64).
     *
     * @param password 明文密码
     * @return 哈希字符串
     */
    public String hashPassword(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = sha256(password, salt);
        String saltB64 = Base64.getEncoder().encodeToString(salt);
        String hashB64 = Base64.getEncoder().encodeToString(hash);
        return saltB64 + "$" + hashB64;
    }

    /**
     * 验证密码是否匹配哈希.
     *
     * @param password 明文密码
     * @param storedHash 存储的哈希 (salt$hash)
     * @return 是否匹配
     */
    public boolean verifyPassword(String password, String storedHash) {
        if (storedHash == null || !storedHash.contains("$")) {
            return false;
        }
        String[] parts = storedHash.split("\\$", 2);
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] expected = Base64.getDecoder().decode(parts[1]);
        byte[] actual = sha256(password, salt);
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] sha256(String input, byte[] salt) {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
            md.update(salt);
            md.update(input.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ======================== JWT (HMAC-SHA256, minimal impl) ========================

    /**
     * 生成 JWT 令牌.
     * Header: {"alg":"HS256","typ":"JWT"}
     * Payload: {"sub":"melon","iat":<now>,"exp":<now+ttl>}
     *
     * @return JWT 字符串
     */
    public String generateToken() {
        long now = System.currentTimeMillis() / 1000;
        long exp = now + TOKEN_TTL_SECONDS;

        String header = base64UrlEncode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64UrlEncode(
                "{\"sub\":\"melon\",\"iat\":" + now + ",\"exp\":" + exp + "}");

        String signingInput = header + "." + payload;
        String signature = base64UrlEncode(hmacSha256(signingInput));

        return signingInput + "." + signature;
    }

    /**
     * 验证 JWT 签名和过期时间.
     */
    private boolean validateJwt(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        String signingInput = parts[0] + "." + parts[1];
        String expectedSig = base64UrlEncode(hmacSha256(signingInput));
        if (!MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        // 检查过期时间
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        long exp = extractLongField(payloadJson, "exp");
        return exp > System.currentTimeMillis() / 1000;
    }

    private byte[] hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 not available", e);
        }
    }

    private static String base64UrlEncode(String input) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private long extractLongField(String json, String field) {
        String search = "\"" + field + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return 0;
        int start = idx + search.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ======================== Config Resolution ========================

    private String resolvePasswordHash() {
        // 优先使用预配置的哈希
        String hash = System.getenv(ENV_PASSWORD_HASH);
        if (hash != null && !hash.isBlank()) {
            return hash.trim();
        }
        // 其次使用明文密码, 运行时计算哈希
        String plain = System.getenv(ENV_PASSWORD);
        if (plain != null && !plain.isBlank()) {
            return hashPassword(plain);
        }
        // 均未配置
        return null;
    }

    private String resolveJwtSecret() {
        String secret = System.getenv(ENV_JWT_SECRET);
        if (secret != null && !secret.isBlank()) {
            return secret.trim();
        }
        // 回退: 使用密码哈希作为签名密钥 (保证有密钥可用)
        if (passwordHash != null) {
            return passwordHash;
        }
        // 最终回退: 固定默认密钥 (仅适用于本地开发)
        log.warn("No JWT secret configured, using insecure default");
        return "melon-default-jwt-secret-do-not-use-in-production";
    }

    // ======================== Result Type ========================

    /**
     * 认证结果.
     */
    public static class AuthResult {
        private final boolean success;
        private final String token;
        private final String subject;
        private final String error;

        private AuthResult(boolean success, String token, String subject, String error) {
            this.success = success;
            this.token = token;
            this.subject = subject;
            this.error = error;
        }

        static AuthResult success(String token, String subject) {
            return new AuthResult(true, token, subject, null);
        }

        static AuthResult failure(String error) {
            return new AuthResult(false, null, null, error);
        }

        public boolean isSuccess() { return success; }
        public String getToken() { return token; }
        public String getSubject() { return subject; }
        public String getError() { return error; }

        public Map<String, Object> toMap() {
            if (success) {
                return Map.of("status", "ok", "token", token, "subject", subject);
            }
            return Map.of("status", "error", "error", error);
        }
    }
}
