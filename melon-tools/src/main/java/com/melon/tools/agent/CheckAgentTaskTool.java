/**
 * @author melon
 */
package com.melon.tools.agent;

import reactor.core.publisher.Mono;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Checks the status and result of a previously submitted agent task.
 * Corresponds to Python check_agent_task tool.
 */
public class CheckAgentTaskTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(CheckAgentTaskTool.class);

    public CheckAgentTaskTool() {
        super(ToolBase.builder()
            .name("check_agent_task")
            .description("Check the status and result of a previously submitted agent task by task ID.")
            .inputSchema(parseSchema("""
                {
                  "type": "object",
                  "properties": {
                    "task_id": {
                      "type": "string",
                      "description": "Task ID returned by submit_to_agent"
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
        String taskId = (String) param.getInput().get("task_id");
        if (taskId == null || taskId.isBlank()) {
            return Mono.just(ToolResultBlock.error("task_id is required"));
        }

        log.info("Check agent task: {}", taskId);

        // Look up task in the shared registry
        SubmitToAgentTool.TaskEntry entry = SubmitToAgentTool.getTaskRegistry().get(taskId);
        if (entry == null) {
            return Mono.just(ToolResultBlock.error("Task not found: " + taskId));
        }

        SubmitToAgentTool.TaskStatus status = entry.getStatus();
        StringBuilder result = new StringBuilder();
        result.append("Task ID: ").append(taskId).append("\n");
        result.append("Agent: ").append(entry.getAgentId()).append("\n");
        result.append("Status: ").append(status).append("\n");
        result.append("Task: ").append(entry.getTask()).append("\n");
        result.append("Created: ").append(entry.getCreatedAt()).append("\n");

        if (status == SubmitToAgentTool.TaskStatus.COMPLETED) {
            result.append("Completed: ").append(entry.getCompletedAt()).append("\n");
            String taskResult = entry.getResult();
            result.append("Result: ").append(taskResult != null ? taskResult : "(no result)");
        } else if (status == SubmitToAgentTool.TaskStatus.FAILED) {
            result.append("Completed: ").append(entry.getCompletedAt()).append("\n");
            result.append("Error: ").append(entry.getError() != null ? entry.getError() : "(unknown error)");
        } else {
            // PENDING or RUNNING
            long elapsed = System.currentTimeMillis() - entry.getCreatedAt();
            result.append("Elapsed: ").append(elapsed / 1000).append("s");
        }

        return Mono.just(ToolResultBlock.text(result.toString()));
    }
}
