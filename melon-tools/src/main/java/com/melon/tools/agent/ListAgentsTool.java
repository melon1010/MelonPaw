/**
 * @author melon
 */
package com.melon.tools.agent;

import reactor.core.publisher.Mono;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.core.agent.MultiAgentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lists all registered agents and their statuses.
 * Corresponds to Python list_agents tool.
 */
public class ListAgentsTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(ListAgentsTool.class);

    private final MultiAgentManager agentManager;

    public ListAgentsTool(MultiAgentManager agentManager) {
        super(ToolBase.builder()
            .name("list_agents")
            .description("List all registered agents with their status, workspace, and active model.")
            .inputSchema(parseSchema("""
                {
                  "type": "object",
                  "properties": {},
                  "required": []
                }"""))
            .readOnly(true)
            .concurrencySafe(true));
        this.agentManager = agentManager;
    }

    private static Map<String, Object> parseSchema(String json) {
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        try {
            Map<String, ?> agents = agentManager.listAgents();
            List<String> lines = new ArrayList<>();
            lines.add("Registered agents:");
            agents.forEach((id, info) -> {
                lines.add("  - " + id + ": " + info.toString());
            });
            return Mono.just(ToolResultBlock.text(String.join("\n", lines)));
        } catch (Exception e) {
            log.error("Failed to list agents", e);
            return Mono.just(ToolResultBlock.error("Failed to list agents: " + e.getMessage()));
        }
    }
}
