package com.melon.app.service;

import com.melon.core.config.ConfigManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class TokenUsageServiceSelfCheck {

    private TokenUsageServiceSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-token-usage-check");
        Path workspace = home.resolve("workspaces/melon");
        Path sessions = workspace.resolve("sessions/console");
        Files.createDirectories(sessions);
        Files.writeString(workspace.resolve("agent.json"), """
                {
                  "id": "melon",
                  "active_model": "deepseek:deepseek-v4-flash"
                }
                """);
        Files.writeString(sessions.resolve("default_s1.json"), """
                {
                  "agent": {
                    "state": {
                      "context": [
                        {
                          "id": "u1",
                          "role": "user",
                          "timestamp": "2026-07-02 09:00:00.000",
                          "content": [{"type": "text", "text": "hi"}]
                        },
                        {
                          "id": "a1",
                          "role": "assistant",
                          "timestamp": "2026-07-02 09:00:01.000",
                          "content": [{"type": "text", "text": "hello"}],
                          "metadata": {
                            "_chat_usage": {
                              "inputTokens": 100,
                              "outputTokens": 20,
                              "totalTokens": 120
                            }
                          },
                          "usage": {
                            "inputTokens": 100,
                            "outputTokens": 20,
                            "totalTokens": 120
                          }
                        },
                        {
                          "id": "a2",
                          "role": "assistant",
                          "timestamp": "2026-07-02 09:01:01.000",
                          "content": [{"type": "text", "text": "again"}],
                          "metadata": {
                            "qwenpaw_turn_usage": {
                              "usage": {
                                "prompt_tokens": 30,
                                "completion_tokens": 7,
                                "total_tokens": 37
                              }
                            }
                          }
                        }
                      ]
                    },
                    "scroll": {}
                  }
                }
                """);

        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        TokenUsageService service = new TokenUsageService(configManager);

        List<Map<String, Object>> details = service.getDetails("2026-07-01", "2026-07-03", null, null);
        if (details.size() != 1) {
            throw new AssertionError("expected one date/model usage row: " + details);
        }
        Map<String, Object> row = details.get(0);
        if (!"2026-07-02".equals(row.get("date"))
                || !"deepseek".equals(row.get("provider_id"))
                || !"deepseek-v4-flash".equals(row.get("model"))
                || !Long.valueOf(130).equals(row.get("prompt_tokens"))
                || !Long.valueOf(27).equals(row.get("completion_tokens"))
                || !Long.valueOf(2).equals(row.get("call_count"))) {
            throw new AssertionError("persisted session usage was not aggregated correctly: " + row);
        }

        List<Map<String, Object>> filtered = service.getDetails("2026-07-01", "2026-07-03", "openai", null);
        if (!filtered.isEmpty()) {
            throw new AssertionError("provider filter failed: " + filtered);
        }
    }
}
