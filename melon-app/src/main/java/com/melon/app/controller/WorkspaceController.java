/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作区 API. 对应 Python /api/workspace.
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    private final ConfigManager configManager;
    private final WorkspaceManager workspaceManager;

    public WorkspaceController(ConfigManager configManager) {
        this.configManager = configManager;
        this.workspaceManager = new WorkspaceManager();
    }

    /**
     * 获取工作区信息 (路径、AGENTS.md 内容、目录列表).
     */
    @GetMapping
    public Mono<ResponseEntity<?>> getWorkspace() {
        return Mono.fromCallable(() -> {
            try {
                AgentConfig agentConfig = configManager.getConfig().getAgent("default");
                if (agentConfig == null) {
                    return ResponseEntity.notFound().build();
                }

                Path workspaceDir = workspaceManager.resolveWorkspaceDir(agentConfig);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("path", workspaceDir.toString());

                // Read AGENTS.md
                Path agentsMd = workspaceDir.resolve("AGENTS.md");
                if (Files.exists(agentsMd)) {
                    result.put("agents_md", Files.readString(agentsMd));
                } else {
                    result.put("agents_md", "");
                }

                // List top-level entries
                if (Files.exists(workspaceDir)) {
                    try (var stream = Files.list(workspaceDir)) {
                        result.put("entries", stream
                                .map(p -> p.getFileName().toString())
                                .sorted()
                                .toList());
                    }
                } else {
                    result.put("entries", java.util.List.of());
                }

                return ResponseEntity.ok(result);
            } catch (Exception e) {
                log.error("Failed to get workspace info", e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 更新工作区 (主要更新 AGENTS.md 内容).
     */
    @PutMapping
    public Mono<ResponseEntity<?>> updateWorkspace(@RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            try {
                AgentConfig agentConfig = configManager.getConfig().getAgent("default");
                if (agentConfig == null) {
                    return ResponseEntity.notFound().build();
                }

                Path workspaceDir = workspaceManager.resolveWorkspaceDir(agentConfig);
                Files.createDirectories(workspaceDir);

                // Update AGENTS.md if provided
                String agentsMd = body.get("agents_md");
                if (agentsMd != null) {
                    Path agentsMdFile = workspaceDir.resolve("AGENTS.md");
                    Files.writeString(agentsMdFile, agentsMd);
                    log.info("AGENTS.md updated at {}", agentsMdFile);
                }

                // Update workspace dir if provided
                String newDir = body.get("path");
                if (newDir != null && !newDir.isBlank()) {
                    agentConfig.setWorkspaceDir(newDir);
                    configManager.save();
                    log.info("Workspace dir updated to: {}", newDir);
                }

                return ResponseEntity.ok(Map.of("status", "updated"));
            } catch (IOException e) {
                log.error("Failed to update workspace", e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 初始化工作区 (创建目录结构和默认文件).
     */
    @PostMapping("/init")
    public Mono<ResponseEntity<?>> initWorkspace() {
        return Mono.fromCallable(() -> {
            try {
                AgentConfig agentConfig = configManager.getConfig().getAgent("default");
                if (agentConfig == null) {
                    return ResponseEntity.notFound().build();
                }

                Path workspaceDir = workspaceManager.resolveWorkspaceDir(agentConfig);
                workspaceManager.initWorkspace(workspaceDir);

                return ResponseEntity.ok(Map.of("status", "initialized", "path", workspaceDir.toString()));
            } catch (Exception e) {
                log.error("Failed to init workspace", e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }
}
