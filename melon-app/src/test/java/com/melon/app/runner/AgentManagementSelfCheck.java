package com.melon.app.runner;

import com.melon.core.agent.MultiAgentManager;
import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentManagementSelfCheck {

    private AgentManagementSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-agent-management-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        WorkspaceManager workspaceManager = new WorkspaceManager();
        MultiAgentManager manager = new MultiAgentManager(configManager, workspaceManager);
        manager.init();

        Map<String, AgentConfig> agents = new LinkedHashMap<>(configManager.getConfig().getAgents());
        AgentConfig a = new AgentConfig();
        a.setName("A");
        AgentConfig b = new AgentConfig();
        b.setName("B");
        agents.put("a", a);
        agents.put("b", b);
        configManager.getConfig().setAgents(agents);

        workspaceManager.initWorkspace(configManager.resolveWorkspaceDir("a"));
        workspaceManager.writeAgentJson(configManager.resolveWorkspaceDir("a"), "a", a);
        if (!Files.exists(configManager.resolveWorkspaceDir("a").resolve("agent.json"))) {
            throw new AssertionError("agent workspace was not initialized");
        }

        b.setEnabled(false);
        manager.start("b");
        if (manager.isRunning("b")) {
            throw new AssertionError("disabled agent should not start");
        }
        try {
            manager.getOrCreate("b");
            throw new AssertionError("disabled agent should not be created");
        } catch (IllegalStateException expected) {
            // ok
        }

        LinkedHashMap<String, AgentConfig> ordered = new LinkedHashMap<>();
        ordered.put("b", b);
        ordered.put("default", agents.get("default"));
        ordered.put("a", a);
        configManager.getConfig().setAgents(ordered);
        String first = configManager.getConfig().getAgents().keySet().iterator().next();
        if (!"b".equals(first)) {
            throw new AssertionError("agent order was not preserved");
        }

        ordered.remove("a");
        configManager.getConfig().setAgents(ordered);
        manager.stop("a");
        if (configManager.getConfig().getAgent("a") != null || manager.isRunning("a")) {
            throw new AssertionError("agent delete did not remove config/runtime");
        }
    }
}
