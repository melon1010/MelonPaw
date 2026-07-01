/**
 * @author melon
 */
package com.melon.tools.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.core.cron.CronJobStore;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates a workspace-local QwenPaw cron job.
 */
public class CreateCronJobTool extends ToolBase {

    private final Path workspaceDir;

    public CreateCronJobTool(Path workspaceDir) {
        super(ToolBase.builder()
                .name("create_cron_job")
                .description("Create a scheduled task in the current workspace jobs.json.")
                .inputSchema(parseSchema("""
                    {
                      "type": "object",
                      "properties": {
                        "name": {"type": "string", "description": "Scheduled task name"},
                        "cron": {"type": "string", "description": "5-field cron expression, for example 0 9 * * *"},
                        "prompt": {"type": "string", "description": "Task prompt for the agent to run"},
                        "timezone": {"type": "string", "description": "IANA timezone, default Asia/Shanghai"},
                        "enabled": {"type": "boolean", "description": "Whether the job is enabled"}
                      },
                      "required": ["name", "cron", "prompt"]
                    }"""))
                .readOnly(false)
                .concurrencySafe(false));
        this.workspaceDir = workspaceDir;
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
        String name = text(input.get("name"));
        String cron = text(input.get("cron"));
        String prompt = text(input.get("prompt"));
        String timezone = text(input.getOrDefault("timezone", "Asia/Shanghai"));
        if (name.isBlank()) return Mono.just(ToolResultBlock.error("name is required"));
        if (cron.isBlank()) return Mono.just(ToolResultBlock.error("cron is required"));
        if (prompt.isBlank()) return Mono.just(ToolResultBlock.error("prompt is required"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("enabled", input.getOrDefault("enabled", true));
        body.put("schedule", Map.of("type", "cron", "cron", cron, "timezone", timezone));
        body.put("request", Map.of("input", java.util.List.of(Map.of(
                "role", "user",
                "type", "message",
                "content", java.util.List.of(Map.of("type", "text", "text", prompt))
        ))));
        body.put("dispatch", Map.of("type", "channel", "channel", "console", "target", Map.of("user_id", "default"), "mode", "final", "meta", Map.of()));

        Map<String, Object> job = CronJobStore.create(workspaceDir, body);
        return Mono.just(ToolResultBlock.text("Cron job created: " + job.get("name")
                + "\nid: " + job.get("id")
                + "\ncron: " + job.get("cron")
                + "\nworkspace: " + workspaceDir));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
