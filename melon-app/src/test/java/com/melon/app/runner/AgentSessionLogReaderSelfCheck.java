package com.melon.app.runner;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class AgentSessionLogReaderSelfCheck {

    private AgentSessionLogReaderSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-session-log-check");
        Path stateDir = home.resolve("state/__anon__/s1");
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve("agent_state.json"), """
                {
                  "session_id": "s1",
                  "context": [
                    {
                      "id": "u1",
                      "role": "USER",
                      "content": [{"type":"text","text":"hi<skill name=\\"x\\">hidden</skill>"}],
                      "metadata": {},
                      "timestamp": "2026-07-01 09:00:00.000"
                    },
                    {
                      "id": "a1",
                      "name": "assistant",
                      "role": "ASSISTANT",
                      "content": [
                        {"type":"thinking","thinking":"think"},
                        {"type":"text","text":"I will check."},
                        {"type":"tool_use","id":"c1","name":"execute","input":{"working_directory":"/tmp","command":"pwd"}}
                      ],
                      "metadata": {},
                      "timestamp": "2026-07-01 09:00:01.000"
                    },
                    {
                      "id": "t1",
                      "role": "TOOL",
                      "content": [
                        {"type":"tool_result","id":"c1","name":"execute","output":[{"type":"text","text":"Exit code: 0\\\\n/tmp"}]}
                      ],
                      "metadata": {},
                      "timestamp": "2026-07-01 09:00:02.000"
                    }
                  ]
                }
                """);
        Path sessionDir = home.resolve("workspaces/test/agents/agent/sessions");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("s1.log.jsonl"), """
                {"type":"message","id":"u1","timestamp":1.0,"role":"USER","content":"hi"}
                {"type":"message","id":"a1","timestamp":2.0,"role":"ASSISTANT","content":"I will check.\\n[tool_call: execute({\\"command\\":\\"pwd\\"})]","toolCallId":"c1"}
                {"type":"message","id":"t1","timestamp":3.0,"role":"TOOL","content":"[tool_result: execute] \\"Exit code: 0\\\\n/tmp\\"","toolCallId":"c1"}
                {"type":"message","id":"a2","timestamp":4.0,"role":"ASSISTANT","content":"[tool_call: execute({\\"working_directory\\":\\"/tmp\\",\\"command\\":\\"ls\\"})]","toolCallId":"c2"}
                """);

        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        ChatManager chatManager = new ChatManager(configManager);
        AgentSessionLogReader reader = new AgentSessionLogReader(configManager, chatManager);
        chatManager.saveSessionShadow("test", "console", "default", "s1", Map.of("context", List.of(
                Map.of("id", "shadow-user", "role", "USER", "content", List.of(Map.of("type", "text", "text", "shadow"))),
                Map.of("id", "shadow-file-user", "role", "USER", "content", List.of(
                        Map.of("type", "text", "text", "with attachment"),
                        Map.of("type", "file", "file_url", "report.md", "filename", "report.md"),
                        Map.of("type", "image", "image_url", "photo.png"),
                        Map.of("type", "data", "name", "absolute.md", "source", Map.of(
                                "type", "url",
                                "url", "file:///tmp/absolute.md",
                                "media_type", "application/octet-stream"
                        ))
                )),
                Map.of("id", "shadow-assistant", "role", "ASSISTANT", "content", List.of(
                        Map.of("type", "thinking", "thinking", "shadow-think"),
                        Map.of("type", "tool_call", "id", "shadow-call", "name", "read_file", "input", "{\"file_path\":\"AGENTS.md\"}", "state", "finished"),
                        Map.of("type", "tool_result", "id", "shadow-call", "name", "read_file", "output", List.of(Map.of("type", "text", "text", "ok")), "state", "success"),
                        Map.of("type", "text", "text", "done")
                ))
        )));
        List<Map<String, Object>> shadowMessages = reader.readFrontendMessages("test", "default", "console", "s1");
        if (shadowMessages.size() != 6 || !"shadow".equals(((Map<?, ?>) ((List<?>) shadowMessages.get(0).get("content")).get(0)).get("text"))) {
            throw new AssertionError("python session shadow was not preferred: " + shadowMessages);
        }
        List<?> attachmentContent = (List<?>) shadowMessages.get(1).get("content");
        if (!"report.md".equals(((Map<?, ?>) attachmentContent.get(1)).get("file_url"))
                || !"photo.png".equals(((Map<?, ?>) attachmentContent.get(2)).get("image_url"))
                || !"/tmp/absolute.md".equals(((Map<?, ?>) attachmentContent.get(3)).get("file_url"))) {
            throw new AssertionError("frontend attachment fields were not preserved: " + attachmentContent);
        }
        Files.delete(chatManager.sessionFile("test", "console", "default", "s1"));
        List<Map<String, Object>> messages = reader.readFrontendMessages("test", "s1");

        if (messages.size() != 5) {
            throw new AssertionError("expected state messages, not lossy logs; got " + messages);
        }
        if (!"hi".equals(((Map<?, ?>) ((List<?>) messages.get(0).get("content")).get(0)).get("text"))) {
            throw new AssertionError("injected skill block leaked: " + messages.get(0));
        }
        if (!"reasoning".equals(messages.get(1).get("type")) || !"message".equals(messages.get(2).get("type"))) {
            throw new AssertionError("thinking/text order mismatch: " + messages);
        }
        if (!"plugin_call".equals(messages.get(3).get("type"))) {
            throw new AssertionError("tool call was not converted from state");
        }
        Map<?, ?> stateCallData = (Map<?, ?>) ((Map<?, ?>) ((List<?>) messages.get(3).get("content")).get(0)).get("data");
        if (!"execute_shell_command".equals(stateCallData.get("name"))) {
            throw new AssertionError("state tool name was not normalized: " + stateCallData.get("name"));
        }
        if (!(stateCallData.get("arguments") instanceof String)) {
            throw new AssertionError("state arguments should be JSON string: " + stateCallData);
        }
        Map<?, ?> stateArgs = JsonUtils.getMapper().readValue(String.valueOf(stateCallData.get("arguments")), Map.class);
        if (!"pwd".equals(stateArgs.get("command")) || !"/tmp".equals(stateArgs.get("cwd"))) {
            throw new AssertionError("state shell args were not frontend-compatible: " + stateArgs);
        }
        if (!"plugin_call_output".equals(messages.get(4).get("type"))) {
            throw new AssertionError("state tool result was not converted");
        }
        Map<?, ?> stateResultData = (Map<?, ?>) ((Map<?, ?>) ((List<?>) messages.get(4).get("content")).get(0)).get("data");
        if (!(stateResultData.get("output") instanceof String)
                || !String.valueOf(stateResultData.get("output")).startsWith("[")) {
            throw new AssertionError("state output should match Python JSON-string contract: " + stateResultData);
        }
        if (messages.toString().contains("[tool_call:")) {
            throw new AssertionError("raw tool protocol leaked from state conversion: " + messages);
        }

        Files.delete(stateDir.resolve("agent_state.json"));
        messages = reader.readFrontendMessages("test", "s1");

        if (messages.size() != 5) {
            throw new AssertionError("expected user, prefix text, tool call, tool result, pure tool call; got " + messages.size());
        }
        if (!"plugin_call".equals(messages.get(2).get("type"))) {
            throw new AssertionError("tool call was not converted");
        }
        Map<?, ?> callData = (Map<?, ?>) ((Map<?, ?>) ((List<?>) messages.get(2).get("content")).get(0)).get("data");
        if (!"execute_shell_command".equals(callData.get("name"))) {
            throw new AssertionError("tool name was not normalized: " + callData.get("name"));
        }
        if (!(callData.get("arguments") instanceof String)) {
            throw new AssertionError("arguments should match Python protocol JSON string: " + callData);
        }
        Map<?, ?> arguments = JsonUtils.getMapper().readValue(String.valueOf(callData.get("arguments")), Map.class);
        if (!"pwd".equals(arguments.get("command"))) {
            throw new AssertionError("shell command was not normalized: " + arguments);
        }
        if (arguments.toString().contains("[tool_call:")) {
            throw new AssertionError("raw tool protocol leaked into arguments: " + arguments);
        }
        if (!"plugin_call_output".equals(messages.get(3).get("type"))) {
            throw new AssertionError("tool result was not converted");
        }
        Map<?, ?> resultData = (Map<?, ?>) ((Map<?, ?>) ((List<?>) messages.get(3).get("content")).get(0)).get("data");
        if (!(resultData.get("output") instanceof String)) {
            throw new AssertionError("tool result output should match Python protocol string: " + resultData);
        }
        if (!"plugin_call".equals(messages.get(4).get("type")) || messages.get(4).toString().contains("[tool_call:")) {
            throw new AssertionError("pure tool call was leaked as text: " + messages.get(4));
        }

        Map<String, Object> truncated = FrontendToolCompat.parseArguments("execute", """
                [tool_call: execute({"working_directory":"/tmp","command":"curl -s \\"https://example.com\\"","timeout":20
                """);
        if (!"curl -s \"https://example.com\"".equals(truncated.get("command"))
                || !"/tmp".equals(truncated.get("cwd"))
                || !Long.valueOf(20).equals(truncated.get("timeout"))) {
            throw new AssertionError("truncated shell args were not recovered: " + truncated);
        }

        Path compactedStateDir = home.resolve("state/__anon__/s2");
        Files.createDirectories(compactedStateDir);
        Files.writeString(compactedStateDir.resolve("agent_state.json"), """
                {
                  "session_id": "s2",
                  "context": [
                    {
                      "id": "summary",
                      "role": "USER",
                      "name": "__compaction_summary__",
                      "content": "You are in the middle of a conversation that has been summarized.",
                      "metadata": {}
                    },
                    {
                      "id": "a1",
                      "role": "ASSISTANT",
                      "name": "test",
                      "content": [{"type":"text","text":"after compaction"}],
                      "metadata": {}
                    }
                  ]
                }
                """);
        Files.writeString(sessionDir.resolve("s2.log.jsonl"), """
                {"type":"message","id":"summary-log","timestamp":0.5,"role":"USER","content":"You are in the middle of a conversation that has been summarized.\\n\\n<summary>hidden</summary>"}
                {"type":"message","id":"real-user","timestamp":1.0,"role":"USER","content":"real question"}
                {"type":"message","id":"real-assistant","timestamp":2.0,"role":"ASSISTANT","content":"real answer"}
                """);
        messages = reader.readFrontendMessages("test", "s2");
        String firstText = String.valueOf(((Map<?, ?>) ((List<?>) messages.get(0).get("content")).get(0)).get("text"));
        if (!"real question".equals(firstText) || messages.toString().contains("__compaction_summary__")) {
            throw new AssertionError("compaction summary leaked instead of jsonl fallback: " + messages);
        }
    }
}
