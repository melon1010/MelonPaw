package com.melon.app.runner;

import com.melon.core.config.ConfigManager;
import com.melon.core.cron.CronJobStore;
import com.melon.core.util.JsonUtils;
import com.melon.tools.cron.CreateCronJobTool;
import io.agentscope.core.tool.ToolCallParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class CronCompatControllerSelfCheck {

    private CronCompatControllerSelfCheck() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-cron-compat-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());

        Map<String, Object> job = CronJobStore.normalize(Map.of(
                "name", "weather",
                "schedule", Map.of("type", "cron", "cron", "0 9 * * *", "timezone", "Asia/Shanghai"),
                "request", Map.of("input", List.of(Map.of(
                        "role", "user",
                        "type", "message",
                        "content", List.of(Map.of("type", "text", "text", "南京天气"))
                ))),
                "dispatch", Map.of("type", "channel", "channel", "console", "target", Map.of("user_id", "u1"), "mode", "final")
        ), "job-1");
        CronJobStore.save(configManager.resolveWorkspaceDir("agent-a"), List.of(job));

        Map<String, Object> stored = JsonUtils.loadAsMap(home.resolve("workspaces/agent-a/jobs.json"));
        if (!(stored.get("jobs") instanceof List<?> jobs) || jobs.size() != 1) {
            throw new AssertionError("workspace jobs.json was not written: " + stored);
        }
        String text = stored.toString();
        if (!text.contains("0 9 * * *") || !text.contains("南京天气") || !text.contains("dispatch")) {
            throw new AssertionError("python-style cron job fields missing: " + stored);
        }
        if (Files.exists(home.resolve("crons.json"))) {
            throw new AssertionError("compat cron should not write global crons.json");
        }

        new CreateCronJobTool(configManager.resolveWorkspaceDir("agent-b"))
                .callAsync(ToolCallParam.builder().input(Map.of(
                        "name", "tool-weather",
                        "cron", "0 8 * * *",
                        "prompt", "上海天气"
                )).build())
                .block();
        Map<String, Object> toolStored = JsonUtils.loadAsMap(home.resolve("workspaces/agent-b/jobs.json"));
        if (!toolStored.toString().contains("tool-weather")) {
            throw new AssertionError("cron tool did not write workspace jobs.json: " + toolStored);
        }
    }
}
