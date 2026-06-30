/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.core.util.JsonUtils;
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

    private final Path cronsFile;

    public CronController() {
        this.cronsFile = Path.of(System.getProperty("user.home"), ".melon", "crons.json");
    }

    /**
     * 列出所有定时任务.
     */
    @GetMapping
    public Mono<ResponseEntity<?>> listCrons() {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> crons = loadCrons();
            return ResponseEntity.ok(crons);
        });
    }

    /**
     * 创建定时任务.
     */
    @PostMapping
    public Mono<ResponseEntity<?>> createCron(@RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            try {
                String name = (String) body.get("name");
                String cron = (String) body.get("cron");
                String prompt = (String) body.get("prompt");

                if (name == null || cron == null || prompt == null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "name, cron, and prompt are required"));
                }

                List<Map<String, Object>> crons = loadCrons();

                // Check for duplicate name
                boolean exists = crons.stream()
                        .anyMatch(c -> name.equals(c.get("name")));
                if (exists) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Cron with name '" + name + "' already exists"));
                }

                Map<String, Object> cronEntry = new LinkedHashMap<>();
                cronEntry.put("id", UUID.randomUUID().toString());
                cronEntry.put("name", name);
                cronEntry.put("cron", cron);
                cronEntry.put("prompt", prompt);
                cronEntry.put("enabled", body.getOrDefault("enabled", true));
                cronEntry.put("created_at", System.currentTimeMillis());

                crons.add(cronEntry);
                saveCrons(crons);

                log.info("Cron created: {} ({})", name, cron);
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
    public Mono<ResponseEntity<?>> deleteCron(@PathVariable String name) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> crons = loadCrons();
            boolean removed = crons.removeIf(c -> name.equals(c.get("name")));
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
            saveCrons(crons);
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
            @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            Boolean enabled = (Boolean) body.get("enabled");
            if (enabled == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "'enabled' field is required"));
            }

            List<Map<String, Object>> crons = loadCrons();
            boolean found = false;
            for (Map<String, Object> cron : crons) {
                if (name.equals(cron.get("name"))) {
                    cron.put("enabled", enabled);
                    found = true;
                    break;
                }
            }

            if (!found) {
                return ResponseEntity.notFound().build();
            }

            saveCrons(crons);
            log.info("Cron '{}' enabled={}", name, enabled);
            return ResponseEntity.ok(Map.of("name", name, "enabled", enabled));
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadCrons() {
        var raw = JsonUtils.loadAsMap(cronsFile);
        Object list = raw.get("crons");
        if (list instanceof List) {
            return new ArrayList<>((List<Map<String, Object>>) list);
        }
        return new ArrayList<>();
    }

    private void saveCrons(List<Map<String, Object>> crons) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("crons", crons);
        JsonUtils.save(cronsFile, wrapper);
    }
}
