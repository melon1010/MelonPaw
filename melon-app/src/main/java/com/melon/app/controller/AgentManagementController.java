/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Agent 管理 CRUD. 对应 Python /api/agents.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentManagementController {

    @Autowired
    private ConfigManager configManager;

    @GetMapping
    public Mono<Map<String, AgentConfig>> list() {
        return Mono.just(configManager.getConfig().getAgents());
    }

    @GetMapping("/{id}")
    public Mono<AgentConfig> get(@PathVariable String id) {
        return Mono.justOrEmpty(configManager.getConfig().getAgent(id));
    }
}
