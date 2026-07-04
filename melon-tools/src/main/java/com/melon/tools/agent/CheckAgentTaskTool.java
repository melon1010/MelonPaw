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
 * Checks background inter-agent task status. Mirrors Python check_agent_task.
 */
public class CheckAgentTaskTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(CheckAgentTaskTool.class);

    public CheckAgentTaskTool() {
        super(ToolBase.builder()
            .name("check_agent_task")
            .description("Check the status and final result of a previously submitted background agent task.")
            .inputSchema(parseSchema("""
                {
                  "type": "object",
                  "properties": {
                    "task_id": {
                      "type": "string",
                      "description": "Task ID returned by submit_to_agent or spawn_subagent(background=true)"
                    }
                  },
                  "required": ["task_id"]
                }"""))
            .readOnly(true)
            .concurrencySafe(true));
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
        String taskId = AgentChatBridge.normalizeId(param.getInput().get("task_id"));
        if (taskId == null) {
            return Mono.just(ToolResultBlock.error("ERROR: 'task_id' is required to check task status"));
        }

        log.info("check_agent_task: {}", taskId);
        SubmitToAgentTool.TaskEntry entry = SubmitToAgentTool.getTask(taskId);
        if (entry == null) {
            return Mono.just(ToolResultBlock.error("Task not found: " + taskId));
        }
        return Mono.just(ToolResultBlock.text(formatBackgroundStatusText(entry)));
    }

    public static String formatBackgroundStatusText(SubmitToAgentTool.TaskEntry entry) {
        String status = entry.getLifecycleStatus();
        StringBuilder result = new StringBuilder();
        result.append("[TASK_ID: ").append(entry.getTaskId()).append("]\n");
        result.append("[STATUS: ").append(status).append("]\n\n");

        if ("finished".equals(status)) {
            if ("completed".equals(entry.getResultStatus())) {
                result.append("Task completed.\n\n");
                result.append(AgentChatBridge.formatAgentChatText(entry.getResult(), entry.getSessionId()));
            } else {
                result.append("Task failed.\n\n");
                result.append("Error: ").append(entry.getError() != null ? entry.getError() : "Unknown error");
            }
            return result.toString();
        }

        if ("running".equals(status)) {
            result.append("Task is still running...\n");
            double started = entry.getStartedAt() > 0 ? entry.getStartedAt() / 1000.0 : entry.getCreatedAt() / 1000.0;
            result.append("Started at: ").append(started);
        } else if ("submitted".equals(status)) {
            result.append("Task submitted, waiting to start...");
        } else {
            result.append("Task status: ").append(status);
        }
        return result.toString();
    }
}
