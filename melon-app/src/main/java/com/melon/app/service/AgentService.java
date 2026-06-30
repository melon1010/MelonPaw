/**
 * @author melon
 */
package com.melon.app.service;

import com.melon.core.agent.MultiAgentManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for agent management operations.
 * Wraps MultiAgentManager with business logic.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final MultiAgentManager agentManager;
    private final ConfigManager configManager;

    public AgentService(MultiAgentManager agentManager, ConfigManager configManager) {
        this.agentManager = agentManager;
        this.configManager = configManager;
    }

    /**
     * Lists all configured agents.
     */
    public List<Map<String, Object>> listAgents() {
        Map<String, AgentConfig> agents = configManager.getConfig().getAgents();
        List<Map<String, Object>> result = new ArrayList<>();
        agents.forEach((id, config) -> {
            result.add(Map.of(
                "id", id,
                "name", config.getName() != null ? config.getName() : id,
                "active_model", config.getActiveModel(),
                "running", agentManager.isRunning(id)
            ));
        });
        return result;
    }

    /**
     * Gets agent configuration.
     */
    public AgentConfig getAgentConfig(String agentId) {
        return configManager.getConfig().getAgents().get(agentId);
    }

    /**
     * Creates or updates an agent configuration.
     */
    public void saveAgentConfig(String agentId, AgentConfig config) {
        configManager.getConfig().getAgents().put(agentId, config);
        configManager.save();
        agentManager.reload(agentId);
        log.info("Agent config saved: {}", agentId);
    }

    /**
     * Deletes an agent.
     */
    public void deleteAgent(String agentId) {
        agentManager.stop(agentId);
        configManager.getConfig().getAgents().remove(agentId);
        configManager.save();
        log.info("Agent deleted: {}", agentId);
    }

    /**
     * Starts an agent.
     */
    public void startAgent(String agentId) {
        agentManager.start(agentId);
        log.info("Agent started: {}", agentId);
    }

    /**
     * Stops a running agent.
     */
    public void stopAgent(String agentId) {
        agentManager.stop(agentId);
        log.info("Agent stopped: {}", agentId);
    }

    /**
     * Reloads an agent's configuration.
     */
    public void reloadAgent(String agentId) {
        agentManager.reload(agentId);
        log.info("Agent reloaded: {}", agentId);
    }
}
