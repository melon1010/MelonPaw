/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.app.service.EnvService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 环境变量 API. 对应 Python /api/envs.
 */
@RestController
@RequestMapping("/api/envs")
public class EnvController {

    private static final Logger log = LoggerFactory.getLogger(EnvController.class);

    private final EnvService envService;

    public EnvController(EnvService envService) {
        this.envService = envService;
    }

    /**
     * 列出所有环境变量 (敏感字段打码).
     */
    @GetMapping
    public Mono<ResponseEntity<?>> listEnvs() {
        return Mono.fromCallable(() -> {
            Map<String, String> envs = envService.listEnvs();
            return ResponseEntity.ok(envs);
        });
    }

    /**
     * 获取单个环境变量.
     */
    @GetMapping("/{key}")
    public Mono<ResponseEntity<?>> getEnv(@PathVariable String key) {
        return Mono.fromCallable(() -> {
            String value = envService.getEnv(key);
            if (value == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("key", key, "value", value));
        });
    }

    /**
     * 设置环境变量 (新增或更新).
     */
    @PutMapping("/{key}")
    public Mono<ResponseEntity<?>> setEnv(
            @PathVariable String key,
            @RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            try {
                String value = body.get("value");
                if (value == null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "'value' field is required"));
                }
                envService.setEnv(key, value);
                log.info("Env updated: {}", key);
                return ResponseEntity.ok(Map.of("status", "set", "key", key));
            } catch (Exception e) {
                log.error("Failed to set env: {}", key, e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 删除环境变量.
     */
    @DeleteMapping("/{key}")
    public Mono<ResponseEntity<?>> deleteEnv(@PathVariable String key) {
        return Mono.fromCallable(() -> {
            boolean deleted = envService.deleteEnv(key);
            if (!deleted) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("status", "deleted", "key", key));
        });
    }
}
