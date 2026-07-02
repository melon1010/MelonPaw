package com.melon.core.agent;

import com.melon.core.config.AgentConfig;
import io.agentscope.core.tool.Toolkit;

import java.nio.file.Path;

/**
 * Optional hook for app-layer integrations that need to add tools to an agent toolkit.
 */
@FunctionalInterface
public interface ToolkitContributor {
    void contribute(String agentId, AgentConfig agentConfig, Path workspaceDir, Toolkit toolkit);
}
