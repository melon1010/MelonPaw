package com.melon.core.agent;

import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import com.melon.core.middleware.TokenRecordingMiddleware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多 Agent 管理器. 对应 Python app/multi_agent_manager.py.
 * 管理 HarnessAgent 单例, 按配置创建/重载.
 */
public class MultiAgentManager {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentManager.class);

    private final Map<String, HarnessAgent> agents = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final MelonAgentFactory agentFactory;
    private final ConfigManager configManager;
    private final WorkspaceManager workspaceManager;
    private AgentStateStore stateStore;

    public MultiAgentManager(ConfigManager configManager, WorkspaceManager workspaceManager) {
        this(configManager, workspaceManager, null);
    }

    public MultiAgentManager(ConfigManager configManager, WorkspaceManager workspaceManager,
                             TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback) {
        this.configManager = configManager;
        this.agentFactory = new MelonAgentFactory(this, tokenUsageCallback);
        this.workspaceManager = workspaceManager;
    }

    /**
     * 初始化状态存储.
     */
    public void init() {
        String storeType = configManager.stateStoreType();
        Path statePath = configManager.resolveStateDir();

        if ("json_file".equals(storeType) || "json".equals(storeType)) {
            this.stateStore = new JsonFileAgentStateStore(statePath);
        } else if ("redis".equals(storeType)) {
            log.warn("Redis state store not yet configured, falling back to JSON file");
            this.stateStore = new JsonFileAgentStateStore(statePath);
        } else {
            this.stateStore = new JsonFileAgentStateStore(statePath);
        }
        log.info("State store initialized: type={}, path={}", storeType, statePath);
    }

    /**
     * 获取或创建 Agent 单例.
     */
    public HarnessAgent getOrCreate(String agentId) {
        return agents.computeIfAbsent(agentId, id -> {
            AgentConfig config = configManager.getConfig().getAgents().get(id);
            if (config == null) {
                log.warn("Agent config not found for id={}, using default", id);
                config = configManager.getConfig().getAgents().get("default");
            }
            if (config == null) {
                throw new IllegalArgumentException("No agent config found for: " + id);
            }
            if (!config.isEnabled()) {
                throw new IllegalStateException("Agent is disabled: " + id);
            }
            Path workspaceDir = configManager.resolveWorkspaceDir(id);
            workspaceManager.initWorkspace(workspaceDir);
            workspaceManager.writeAgentJson(workspaceDir, id, config);
            return agentFactory.create(id, config, workspaceDir, stateStore);
        });
    }

    /**
     * 重载 Agent 配置 (配置变更时调用).
     */
    public void reload(String agentId) {
        HarnessAgent old = agents.remove(agentId);
        running.remove(agentId);
        if (old != null) {
            log.info("Agent {} removed for reload", agentId);
        }
    }

    /**
     * 重载所有 Agent.
     */
    public void reloadAll() {
        agents.clear();
        running.clear();
        log.info("All agents cleared for reload");
    }

    /**
     * 启动指定 Agent.
     */
    public void start(String agentId) {
        AgentConfig config = configManager.getConfig().getAgents().get(agentId);
        if (config != null && !config.isEnabled()) {
            log.info("Agent {} is disabled, skip start", agentId);
            return;
        }
        getOrCreate(agentId);
        running.add(agentId);
        log.info("Agent {} started", agentId);
    }

    /**
     * 停止指定 Agent.
     */
    public void stop(String agentId) {
        running.remove(agentId);
        HarnessAgent removed = agents.remove(agentId);
        if (removed != null) {
            log.info("Agent {} stopped and removed", agentId);
        }
    }

    /**
     * 检查 Agent 是否在运行.
     */
    public boolean isRunning(String agentId) {
        return running.contains(agentId) && agents.containsKey(agentId);
    }

    /**
     * 列出所有 Agent 及其状态.
     */
    public Map<String, Map<String, Object>> listAgents() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        Map<String, AgentConfig> configs = configManager.getConfig().getAgents();
        for (String id : configs.keySet()) {
            AgentConfig config = configs.get(id);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", config.getName() != null ? config.getName() : id);
            info.put("active_model", config.getActiveModel());
            info.put("enabled", config.isEnabled());
            info.put("running", isRunning(id));
            result.put(id, info);
        }
        return result;
    }

    /**
     * 获取 Agent 实例.
     */
    public HarnessAgent getAgent(String agentId) {
        return agents.get(agentId);
    }

    /**
     * 启动所有配置的 Agent (预热).
     */
    public void startAll() {
        for (String agentId : configManager.getConfig().getAgents().keySet()) {
            try {
                AgentConfig config = configManager.getConfig().getAgents().get(agentId);
                if (config != null && !config.isEnabled()) {
                    continue;
                }
                start(agentId);
            } catch (Exception e) {
                log.error("Failed to start agent {}", agentId, e);
            }
        }
    }

    /**
     * 停止所有 Agent.
     */
    public void stopAll() {
        for (String agentId : new ArrayList<>(running)) {
            stop(agentId);
        }
        log.info("All agents stopped");
    }
}
