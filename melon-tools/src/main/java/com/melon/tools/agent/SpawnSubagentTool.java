package com.melon.tools.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Spawns an ephemeral subagent in the current agent/workspace.
 */
public class SpawnSubagentTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(SpawnSubagentTool.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 600;

    public SpawnSubagentTool() {
        super(ToolBase.builder()
            .name("spawn_subagent")
            .description("Spawn an ephemeral subagent within the current workspace for a one-shot task.")
            .inputSchema(parseSchema("""
                {
                  "type": "object",
                  "properties": {
                    "task": {
                      "type": "string",
                      "description": "Subtask prompt for the ephemeral subagent"
                    },
                    "fork": {
                      "type": "boolean",
                      "description": "Run in an isolated fork/worktree. Not yet supported by the Java implementation.",
                      "default": false
                    },
                    "background": {
                      "type": "boolean",
                      "description": "Submit as a background task and return immediately with a task_id",
                      "default": false
                    },
                    "timeout": {
                      "type": "integer",
                      "description": "Foreground wait timeout in seconds",
                      "default": 600
                    }
                  },
                  "required": ["task"]
                }"""))
            .readOnly(false)
            .concurrencySafe(false));
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
        Map<String, Object> input = param.getInput();
        String task = AgentChatBridge.text(input, "task");
        boolean fork = AgentChatBridge.booleanValue(input.get("fork"), false);
        boolean background = AgentChatBridge.booleanValue(input.get("background"), false);
        long timeout = AgentChatBridge.longValue(input.get("timeout"), DEFAULT_TIMEOUT_SECONDS);

        if (task == null || task.isBlank()) {
            return Mono.just(ToolResultBlock.error("ERROR: 'task' is required for spawn_subagent"));
        }
        if (fork) {
            return Mono.just(ToolResultBlock.error("ERROR: spawn_subagent(fork=true) is not implemented in the Java runtime yet"));
        }

        String currentAgentId = AgentChatBridge.callingAgentId(param);
        String sessionId = AgentChatBridge.generateSubagentSessionId();
        AgentChatBridge.AgentRequest request = new AgentChatBridge.AgentRequest(
                currentAgentId,
                task,
                sessionId,
                currentAgentId,
                AgentChatBridge.rootSessionId(param),
                timeout
        );

        if (background) {
            SubmitToAgentTool.TaskEntry entry = SubmitToAgentTool.submit(request, null);
            return Mono.just(ToolResultBlock.text(SubmitToAgentTool.formatBackgroundSubmissionText(entry)));
        }

        return Mono.fromCallable(() -> {
            log.info("spawn_subagent: agent={}, session={}", currentAgentId, sessionId);
            String reply = AgentChatBridge.execute(request);
            return ToolResultBlock.text(AgentChatBridge.formatAgentChatText(reply, sessionId));
        }).onErrorResume(e -> Mono.just(ToolResultBlock.error(e.getMessage())));
    }
}
