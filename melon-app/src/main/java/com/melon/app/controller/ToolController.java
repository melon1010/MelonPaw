/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.core.config.ConfigManager;
import com.melon.core.config.ToolsConfig;
import com.melon.core.config.BuiltinToolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 工具管理 API. 对应 Python /api/tools.
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private static final Logger log = LoggerFactory.getLogger(ToolController.class);

    @Autowired
    private ConfigManager configManager;

    /**
     * 列出所有工具及其启用状态.
     */
    @GetMapping
    public Mono<ResponseEntity<?>> listTools() {
        return Mono.fromCallable(() -> {
            ToolsConfig toolsConfig = configManager.getConfig()
                    .getAgents().get("default").getTools();
            return ResponseEntity.ok(toolsConfig.getBuiltinTools());
        });
    }

    /**
     * 更新工具的启用状态.
     */
    @PutMapping("/{name}/enabled")
    public Mono<ResponseEntity<?>> toggleTool(
            @PathVariable String name,
            @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            try {
                Boolean enabled = (Boolean) body.get("enabled");
                if (enabled == null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "'enabled' field is required"));
                }

                ToolsConfig toolsConfig = configManager.getConfig()
                        .getAgents().get("default").getTools();
                BuiltinToolConfig tool = toolsConfig.getBuiltinTools().get(name);

                if (tool == null) {
                    return ResponseEntity.notFound().build();
                }

                tool.setEnabled(enabled);
                configManager.save();
                log.info("Tool '{}' enabled={}", name, enabled);

                return ResponseEntity.ok(Map.of("name", name, "enabled", enabled));
            } catch (Exception e) {
                log.error("Failed to toggle tool: {}", name, e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }
}
