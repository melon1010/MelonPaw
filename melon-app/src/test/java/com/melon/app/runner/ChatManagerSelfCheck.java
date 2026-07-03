package com.melon.app.runner;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ChatManagerSelfCheck {

    private ChatManagerSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-chat-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        ChatManager manager = new ChatManager(configManager);

        ChatSpec a = manager.getOrCreateForSession("default", "s1", "u1", "console", "hello");
        ChatSpec same = manager.getOrCreateForSession("default", "s1", "u1", "console", "ignored");
        ChatSpec b = manager.getOrCreateForSession("test", "s1", "u1", "console", "hello");

        if (!a.getId().equals(same.getId())) {
            throw new AssertionError("same session/user/channel should reuse chat");
        }
        if (a.getId().equals(b.getId())) {
            throw new AssertionError("workspace chats should be isolated");
        }
        if (manager.list("default", "u1", "console").size() != 1 || manager.list("test", "u1", "console").size() != 1) {
            throw new AssertionError("workspace chat list/filter mismatch");
        }

        Path defaultChats = home.resolve("workspaces/default/chats.json");
        Map<String, Object> stored = JsonUtils.loadAsMap(defaultChats);
        if (!(stored.get("chats") instanceof List<?> chats) || chats.size() != 1) {
            throw new AssertionError("workspace chats.json was not written: " + stored);
        }

        manager.saveSessionShadow("default", "console", "u1", "s1", Map.of("context", List.of()));
        if (!Files.isRegularFile(home.resolve("workspaces/default/sessions/console/u1_s1.json"))) {
            throw new AssertionError("session shadow was not written");
        }
        manager.saveSessionShadowFromStateStore("default", "console", "u1", "s2", List.of(Map.of(
                "id", "frontend-user",
                "role", "user",
                "content", List.of(
                        Map.of("type", "text", "text", "with file"),
                        Map.of("type", "file", "file_url", "a.txt", "filename", "a.txt")
                )
        )));
        Map<String, Object> shadowOnly = JsonUtils.loadAsMap(home.resolve("workspaces/default/sessions/console/u1_s2.json"));
        if (!shadowOnly.toString().contains("a.txt")) {
            throw new AssertionError("frontend-only session shadow did not preserve attachments: " + shadowOnly);
        }
        Map<?, ?> shadowOnlyAgent = (Map<?, ?>) shadowOnly.get("agent");
        Map<?, ?> shadowOnlyScroll = (Map<?, ?>) shadowOnlyAgent.get("scroll");
        if (((List<?>) shadowOnlyScroll.get("persisted_ids")).isEmpty()
                || ((Map<?, ?>) shadowOnlyScroll.get("seq_by_id")).isEmpty()) {
            throw new AssertionError("scroll index should be populated for frontend-only shadow: " + shadowOnlyScroll);
        }

        Path stateDir = home.resolve("state/default/s3");
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve("agent_state.json"), """
                {"context":[{"id":"u-state","role":"USER","content":[{"type":"text","text":"with image"}]}]}
                """);
        manager.saveSessionShadowFromStateStore("default", "console", "u1", "s3", List.of(Map.of(
                "id", "frontend-image",
                "role", "user",
                "content", List.of(
                        Map.of("type", "text", "text", "with image"),
                        Map.of("type", "image", "image_url", "img.png")
                )
        )));
        Map<String, Object> merged = JsonUtils.loadAsMap(home.resolve("workspaces/default/sessions/console/u1_s3.json"));
        String mergedText = merged.toString();
        if (!mergedText.contains("img.png") || mergedText.contains("frontend-image")) {
            throw new AssertionError("frontend message should replace matching state user content without duplication: " + merged);
        }

        String bigOutput = "x".repeat(13000);
        manager.saveSessionShadow("default", "console", "u1", "s4", Map.of("context", List.of(
                Map.of("id", "a1", "role", "ASSISTANT", "content", List.of(
                        Map.of("type", "tool_use", "id", "c1", "name", "execute", "input", Map.of("command", "pwd"))
                )),
                Map.of("id", "t1", "role", "TOOL", "content", List.of(
                        Map.of("type", "tool_result", "id", "c1", "name", "execute", "output", bigOutput)
                ))
        )));
        Map<String, Object> toolShadow = JsonUtils.loadAsMap(home.resolve("workspaces/default/sessions/console/u1_s4.json"));
        String toolShadowText = toolShadow.toString();
        if (toolShadowText.contains("\"TOOL\"") || toolShadowText.contains("tool_use")) {
            throw new AssertionError("tool state should be normalized to python shape: " + toolShadow);
        }
        if (toolShadowText.contains("state=running") || toolShadowText.contains("\"state\":\"running\"")) {
            throw new AssertionError("tool result state should be finalized: " + toolShadow);
        }
        if (!toolShadowText.contains("tool_call") || !toolShadowText.contains("tool_result")) {
            throw new AssertionError("tool call/result missing after normalization: " + toolShadow);
        }
        if (!Files.isDirectory(home.resolve("workspaces/default/tool_results"))
                || Files.list(home.resolve("workspaces/default/tool_results")).findAny().isEmpty()) {
            throw new AssertionError("large tool output was not spilled");
        }
        Map<?, ?> agent = (Map<?, ?>) toolShadow.get("agent");
        Map<?, ?> scroll = (Map<?, ?>) agent.get("scroll");
        if (((List<?>) scroll.get("persisted_tcids")).size() < 2
                || ((Map<?, ?>) scroll.get("model_turn_seq")).isEmpty()
                || ((Map<?, ?>) scroll.get("model_turn_nblk")).isEmpty()) {
            throw new AssertionError("scroll tool indexes were not populated: " + scroll);
        }

        Path compactState = home.resolve("state/default/s5");
        Files.createDirectories(compactState);
        Files.writeString(compactState.resolve("agent_state.json"), """
                {"context":[
                  {"id":"summary","role":"USER","name":"__compaction_summary__","content":[{"type":"text","text":"hidden"}]},
                  {"id":"a1","role":"ASSISTANT","content":[{"type":"text","text":"answer"}]},
                  {"id":"t1","role":"TOOL","content":[{"type":"tool_result","id":"c2","name":"read_file","state":"running","output":[{"type":"text","text":"ok"}]}]}
                ]}
                """);
        manager.saveSessionShadowFromStateStore("default", "console", "u1", "s5", List.of(Map.of(
                "id", "frontend-user-s5",
                "role", "user",
                "content", List.of(Map.of("type", "text", "text", "real question"))
        )));
        Map<String, Object> ordered = JsonUtils.loadAsMap(home.resolve("workspaces/default/sessions/console/u1_s5.json"));
        List<?> orderedContext = (List<?>) ((Map<?, ?>) ((Map<?, ?>) ordered.get("agent")).get("state")).get("context");
        if (ordered.toString().contains("__compaction_summary__")) {
            throw new AssertionError("internal compaction summary leaked into frontend shadow: " + ordered);
        }
        Map<?, ?> firstMessage = (Map<?, ?>) orderedContext.get(0);
        if (!"user".equals(firstMessage.get("role")) || !ordered.toString().contains("real question")) {
            throw new AssertionError("frontend user message should be inserted before assistant output: " + ordered);
        }
        if (ordered.toString().contains("running")) {
            throw new AssertionError("tool result state should not remain running: " + ordered);
        }

        Path compactStateWithLog = home.resolve("state/default/s6");
        Files.createDirectories(compactStateWithLog);
        Files.writeString(compactStateWithLog.resolve("agent_state.json"), """
                {"context":[
                  {"id":"summary","role":"USER","name":"__compaction_summary__","content":[{"type":"text","text":"bad summary"}]},
                  {"id":"late","role":"ASSISTANT","content":[{"type":"text","text":"late answer"}]}
                ]}
                """);
        Path sessionLog = home.resolve("workspaces/default/agents/default/sessions");
        Files.createDirectories(sessionLog);
        Files.writeString(sessionLog.resolve("s6.log.jsonl"), """
                {"type":"message","id":"summary-log","timestamp":0.5,"role":"USER","content":"You are in the middle of a conversation that has been summarized.\\n\\n<summary>hidden</summary>"}
                {"type":"message","id":"u-log","timestamp":1.0,"role":"USER","content":"original question"}
                {"type":"message","id":"a-log","timestamp":2.0,"role":"ASSISTANT","content":"I will read.\\n[tool_call: read_file({\\"file_path\\":\\"AGENTS.md\\"})]","toolCallId":"call-read"}
                {"type":"message","id":"t-log","timestamp":3.0,"role":"TOOL","content":"[tool_result: read_file] ok","toolCallId":"call-read"}
                """);
        manager.saveSessionShadowFromStateStore("default", "console", "u1", "s6", List.of());
        Map<String, Object> repaired = JsonUtils.loadAsMap(home.resolve("workspaces/default/sessions/console/u1_s6.json"));
        String repairedText = repaired.toString();
        if (!repairedText.contains("original question") || repairedText.contains("bad summary") || repairedText.contains("<summary>hidden</summary>")) {
            throw new AssertionError("compacted state should be repaired from jsonl before saving: " + repaired);
        }

        Path legacyDir = home.resolve("chats");
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("legacy.json"), """
                {
                  "id":"legacy",
                  "agent_id":"legacy-agent",
                  "title":"old",
                  "session_id":"old-session",
                  "created_at":"2026-07-01T00:00:00Z",
                  "updated_at":"2026-07-01T00:00:00Z"
                }
                """);
        ChatSpec legacy = manager.get("legacy-agent", "legacy");
        if (legacy == null || !"old".equals(legacy.getName())) {
            throw new AssertionError("legacy chat was not imported: " + legacy);
        }
        if (!Files.exists(legacyDir.resolve("legacy.json"))) {
            throw new AssertionError("legacy import must not delete old file");
        }
    }
}
