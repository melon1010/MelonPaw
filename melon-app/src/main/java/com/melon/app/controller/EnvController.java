package com.melon.app.controller;

import com.melon.app.service.EnvService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
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
        return Mono.fromCallable(() -> ResponseEntity.ok(envList()));
    }

    @PutMapping
    public Mono<ResponseEntity<?>> saveEnvs(@RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            for (String key : envService.listEnvs().keySet()) {
                envService.deleteEnv(key);
            }
            if (body != null) {
                body.forEach(envService::setEnv);
            }
            return ResponseEntity.ok(envList());
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
                if (body == null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("detail", "'value' field is required"));
                }
                String value = body.get("value");
                if (value == null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("detail", "'value' field is required"));
                }
                envService.setEnv(key, value);
                log.info("Env updated: {}", key);
                return ResponseEntity.ok(envList());
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
            envService.deleteEnv(key);
            return ResponseEntity.ok(envList());
        });
    }

    private List<Map<String, String>> envList() {
        return envService.listEnvs().entrySet().stream()
                .map(entry -> {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("key", entry.getKey());
                    item.put("value", entry.getValue() == null ? "" : entry.getValue());
                    return item;
                })
                .toList();
    }
}
