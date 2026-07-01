/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.core.config.ConfigManager;
import com.melon.core.cron.CronJobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.*;

/**
 * 定时任务 API. 对应 Python /api/crons.
 * 管理定时任务的增删查.
 */
@RestController
@RequestMapping("/api/crons")
public class CronController {

    private static final Logger log = LoggerFactory.getLogger(CronController.class);

    private final ConfigManager configManager;

    public CronController(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * 列出所有定时任务.
     */
    @GetMapping
    public Mono<ResponseEntity<?>> listCrons(@RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> crons = loadCrons(agentId);
            return ResponseEntity.ok(crons);
        });
    }

    /**
     * 创建定时任务.
     */
    @PostMapping
    public Mono<ResponseEntity<?>> createCron(@RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            try {
                String name = (String) body.get("name");

                if (name == null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "name is required"));
                }

                List<Map<String, Object>> crons = loadCrons(agentId);

                // Check for duplicate name
                boolean exists = crons.stream()
                        .anyMatch(c -> name.equals(c.get("name")));
                if (exists) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Cron with name '" + name + "' already exists"));
                }

                Map<String, Object> cronEntry = CronJobStore.create(workspaceDir(agentId), body);

                log.info("Cron created: {} ({})", name, cronEntry.get("cron"));
                return ResponseEntity.ok(cronEntry);
            } catch (Exception e) {
                log.error("Failed to create cron", e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 删除定时任务.
     */
    @DeleteMapping("/{name}")
    public Mono<ResponseEntity<?>> deleteCron(@PathVariable String name,
                                              @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            boolean removed = CronJobStore.delete(workspaceDir(agentId), name);
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
            log.info("Cron deleted: {}", name);
            return ResponseEntity.ok(Map.of("status", "deleted", "name", name));
        });
    }

    /**
     * 启用/禁用定时任务.
     */
    @PutMapping("/{name}/enabled")
    public Mono<ResponseEntity<?>> toggleCron(
            @PathVariable String name,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            Boolean enabled = (Boolean) body.get("enabled");
            if (enabled == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "'enabled' field is required"));
            }

            if (!CronJobStore.setEnabled(workspaceDir(agentId), name, enabled)) {
                return ResponseEntity.notFound().build();
            }

            log.info("Cron '{}' enabled={}", name, enabled);
            return ResponseEntity.ok(Map.of("name", name, "enabled", enabled));
        });
    }

    private List<Map<String, Object>> loadCrons(String agentId) {
        return CronJobStore.load(workspaceDir(agentId));
    }

    private Path workspaceDir(String agentId) {
        return configManager.resolveWorkspaceDir(AgentRequestSupport.agentId(agentId));
    }
}
