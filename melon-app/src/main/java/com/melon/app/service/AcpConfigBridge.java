package com.melon.app.service;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import com.melon.tools.agent.DelegateExternalAgentTool;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AcpConfigBridge {
    private final ConfigManager configManager;

    public AcpConfigBridge(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @PostConstruct
    public void load() {
        Map<String, Object> config = JsonUtils.loadAsMap(configManager.resolveStateDir().resolve("acp.json"));
        Object raw = config.get("agents");
        Map<String, Map<String, Object>> agents = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (v instanceof Map<?, ?> values) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    values.forEach((vk, vv) -> copy.put(String.valueOf(vk), vv));
                    agents.put(String.valueOf(k), copy);
                }
            });
        }
        for (String name : List.of("opencode", "qwen_code", "claude_code", "codex")) {
            agents.putIfAbsent(name, defaultAgent(name));
        }
        DelegateExternalAgentTool.setConfig(agents);
    }

    private Map<String, Object> defaultAgent(String name) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", true);
        if ("opencode".equals(name)) {
            config.put("command", "opencode");
            config.put("args", List.of("acp"));
        } else if ("qwen_code".equals(name)) {
            config.put("command", "qwen");
            config.put("args", List.of("--acp"));
        } else if ("claude_code".equals(name)) {
            config.put("command", "npx");
            config.put("args", List.of("-y", "@zed-industries/claude-agent-acp"));
        } else {
            config.put("command", "npx");
            config.put("args", List.of("-y", "@zed-industries/codex-acp"));
        }
        config.put("env", Map.of());
        config.put("trusted", true);
        config.put("tool_parse_mode", "call_title");
        config.put("stdio_buffer_limit_bytes", 50 * 1024 * 1024);
        return config;
    }
}
