package com.melon.app.mcp;

import com.melon.core.agent.ToolkitContributor;
import com.melon.core.config.AgentConfig;
import com.melon.core.util.JsonUtils;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers configured MCP stdio tools into the AgentScope toolkit.
 */
@Component
public class McpToolkitContributor implements ToolkitContributor {

    private static final Logger log = LoggerFactory.getLogger(McpToolkitContributor.class);

    private final McpClientManager mcpClientManager;

    public McpToolkitContributor(McpClientManager mcpClientManager) {
        this.mcpClientManager = mcpClientManager;
    }

    @Override
    public void contribute(String agentId, AgentConfig agentConfig, Path workspaceDir, Toolkit toolkit) {
        for (McpClientManager.McpServerConfig server : mcpClientManager.listServers()) {
            if (!server.isEnabled()) continue;
            if (!mcpClientManager.getConnectionState(server.getId()).connected()) {
                mcpClientManager.connect(server.getId());
            }
            if (!mcpClientManager.getConnectionState(server.getId()).connected()) continue;
            for (McpClientManager.McpTool tool : mcpClientManager.listTools(server.getId())) {
                if (server.getToolWhitelist() != null && !server.getToolWhitelist().contains(tool.getName())) {
                    continue;
                }
                String toolName = safeToolName(tool.getName());
                if (toolName.isBlank()) continue;
                if (toolkit.getToolNames().contains(toolName)) {
                    toolName = safeToolName("mcp_" + server.getId() + "_" + tool.getName());
                }
                toolkit.registerAgentTool(new McpBridgeTool(server.getId(), server.getName(), tool, toolName, mcpClientManager));
                log.info("Registered MCP tool into agent toolkit: server={}, tool={}", server.getId(), toolName);
            }
        }
    }

    private String safeToolName(String name) {
        if (name == null) return "";
        return name.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static final class McpBridgeTool extends ToolBase {
        private final String serverId;
        private final String actualToolName;
        private final McpClientManager mcpClientManager;

        McpBridgeTool(String serverId, String serverName, McpClientManager.McpTool tool, String registeredName,
                      McpClientManager mcpClientManager) {
            super(ToolBase.builder()
                    .name(registeredName)
                    .description(description(serverName, tool))
                    .inputSchema(schema(tool))
                    .readOnly(false)
                    .concurrencySafe(false)
                    .mcp(serverName != null && !serverName.isBlank() ? serverName : serverId));
            this.serverId = serverId;
            this.actualToolName = tool.getName();
            this.mcpClientManager = mcpClientManager;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            Map<String, Object> input = new LinkedHashMap<>(param.getInput());
            return Mono.fromCallable(() -> {
                        Map<String, Object> result = mcpClientManager.callTool(serverId, actualToolName, input);
                        if (result.containsKey("error")) {
                            return ToolResultBlock.error(String.valueOf(result.get("error")));
                        }
                        return ToolResultBlock.text(JsonUtils.toJson(result));
                    })
                    .subscribeOn(Schedulers.boundedElastic());
        }

        private static String description(String serverName, McpClientManager.McpTool tool) {
            String description = tool.getDescription() != null ? tool.getDescription() : "";
            String prefix = serverName == null || serverName.isBlank() ? "MCP tool" : "MCP tool from " + serverName;
            return description.isBlank() ? prefix : prefix + ": " + description;
        }

        private static Map<String, Object> schema(McpClientManager.McpTool tool) {
            Map<String, Object> schema = tool.getInputSchema();
            if (schema == null || schema.isEmpty()) {
                return Map.of("type", "object", "properties", Map.of());
            }
            return schema;
        }
    }
}
