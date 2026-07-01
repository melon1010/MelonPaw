package com.melon.app.runner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads AgentScope session logs and returns the message shape the copied
 * QwenPaw frontend already understands.
 */
@Component
public class AgentSessionLogReader {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionLogReader.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ConfigManager configManager;
    private final ChatManager chatManager;

    public AgentSessionLogReader(ConfigManager configManager, ChatManager chatManager) {
        this.configManager = configManager;
        this.chatManager = chatManager;
    }

    public List<Map<String, Object>> readFrontendMessages(String agentId, String sessionId) {
        return readFrontendMessages(agentId, "default", "console", sessionId);
    }

    public List<Map<String, Object>> readFrontendMessages(String agentId, String userId, String channel, String sessionId) {
        Map<String, Object> shadow = chatManager.loadSessionShadow(agentId, channel, userId, sessionId);
        List<Map<String, Object>> shadowMessages = readPythonSessionMessages(shadow);
        if (!shadowMessages.isEmpty()) return shadowMessages;

        Path stateFile = findStateFile(agentId, sessionId);
        if (stateFile != null) {
            List<Map<String, Object>> messages = readStateMessages(stateFile);
            if (!messages.isEmpty()) return messages;
        }

        Path logFile = findSessionLog(agentId, sessionId);
        if (logFile == null) return List.of();

        List<Map<String, Object>> messages = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(logFile)) {
                if (line.isBlank()) continue;
                Map<String, Object> raw = JsonUtils.getMapper().readValue(line, MAP_TYPE);
                messages.addAll(convert(raw));
            }
        } catch (IOException e) {
            log.warn("Failed to read AgentScope session log: {}", logFile, e);
            return List.of();
        }
        return messages;
    }

    private List<Map<String, Object>> readPythonSessionMessages(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        Object agentRaw = raw.get("agent");
        if (!(agentRaw instanceof Map<?, ?> agentMap)) return List.of();
        Object stateRaw = asMap(agentMap).get("state");
        if (!(stateRaw instanceof Map<?, ?> stateMap)) return List.of();
        Object context = asMap(stateMap).get("context");
        if (!(context instanceof List<?> items)) return List.of();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> message) {
                messages.addAll(convertStateMessage(asMap(message)));
            }
        }
        return messages;
    }

    private Path findStateFile(String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        String id = agentId == null || agentId.isBlank() ? "default" : agentId;
        Path stateDir = configManager.resolveStateDir();
        List<Path> candidates = List.of(
                stateDir.resolve(id).resolve(sessionId).resolve("agent_state.json"),
                stateDir.resolve("__anon__").resolve(sessionId).resolve("agent_state.json")
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        if (!Files.isDirectory(stateDir)) return null;
        try (var stream = Files.find(stateDir, 3,
                (path, attrs) -> attrs.isRegularFile()
                        && path.getFileName().toString().equals("agent_state.json")
                        && path.getParent() != null
                        && path.getParent().getFileName().toString().equals(sessionId))) {
            return stream.sorted(Comparator.comparing(Path::toString)).findFirst().orElse(null);
        } catch (IOException e) {
            log.debug("Failed to scan AgentScope state under {}", stateDir, e);
            return null;
        }
    }

    private List<Map<String, Object>> readStateMessages(Path stateFile) {
        try {
            Map<String, Object> state = JsonUtils.getMapper().readValue(stateFile.toFile(), MAP_TYPE);
            Object context = state.get("context");
            if (!(context instanceof List<?> items)) return List.of();
            List<Map<String, Object>> messages = new ArrayList<>();
            for (Object item : items) {
                if (item instanceof Map<?, ?> raw) {
                    messages.addAll(convertStateMessage(asMap(raw)));
                }
            }
            return messages;
        } catch (IOException e) {
            log.warn("Failed to read AgentScope state: {}", stateFile, e);
            return List.of();
        }
    }

    private List<Map<String, Object>> convertStateMessage(Map<String, Object> raw) {
        String role = lower(raw.get("role"));
        if (role.isBlank()) role = "assistant";
        Object content = raw.get("content");
        Map<String, Object> metadata = stateMetadata(raw);

        if (content instanceof String text) {
            Map<String, Object> message = runtimeMessage(rawId(raw), "message", role, metadata);
            message.put("content", List.of(textContent(stripInjectedSkillBlock(text, role))));
            return List.of(message);
        }
        if (!(content instanceof List<?> blocks)) return List.of();

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> current = null;
        String currentType = null;
        int splitIndex = 0;
        for (Object item : blocks) {
            if (!(item instanceof Map<?, ?> blockRaw)) continue;
            Map<String, Object> block = asMap(blockRaw);
            String blockType = normalizedBlockType(block);
            if ("text".equals(blockType) || isMediaBlock(blockType)) {
                if (!"message".equals(currentType)) {
                    current = startStateMessage(raw, "message", role, metadata, splitIndex++);
                    currentType = "message";
                    messages.add(current);
                }
                contentList(current).add(contentFromBlock(block, blockType, role));
            } else if ("thinking".equals(blockType)) {
                if (!"reasoning".equals(currentType)) {
                    current = startStateMessage(raw, "reasoning", role, metadata, splitIndex++);
                    currentType = "reasoning";
                    messages.add(current);
                }
                contentList(current).add(textContent(stringValue(block.get("thinking"))));
            } else if ("tool_use".equals(blockType) || "tool_call".equals(blockType)) {
                current = null;
                currentType = null;
                messages.add(toolMessage(rawId(raw) + "_tool_" + splitIndex++, role,
                        "plugin_call", stringValue(block.get("name")), stringValue(block.get("id")),
                        toolArgumentsJson(stringValue(block.get("name")), block.get("input")), null, metadata));
            } else if ("tool_result".equals(blockType)) {
                current = null;
                currentType = null;
                messages.add(toolMessage(rawId(raw) + "_tool_result_" + splitIndex++, role,
                        "plugin_call_output", stringValue(block.get("name")), stringValue(block.get("id")),
                        null, jsonStringOrRaw(block.get("output")), metadata));
            } else {
                if (!"message".equals(currentType)) {
                    current = startStateMessage(raw, "message", role, metadata, splitIndex++);
                    currentType = "message";
                    messages.add(current);
                }
                contentList(current).add(textContent(String.valueOf(block)));
            }
        }
        return messages;
    }

    private Path findSessionLog(String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        Path workspace = configManager.resolveWorkspaceDir(agentId);
        List<Path> candidates = List.of(
                workspace.resolve("agents"),
                workspace.resolve("default").resolve("agents")
        );
        for (Path agentsDir : candidates) {
            Path found = findUnderAgents(agentsDir, sessionId + ".log.jsonl");
            if (found != null) return found;
            found = findUnderAgents(agentsDir, sessionId + ".jsonl");
            if (found != null) return found;
        }
        return null;
    }

    private Path findUnderAgents(Path agentsDir, String fileName) {
        if (!Files.isDirectory(agentsDir)) return null;
        try (var stream = Files.find(agentsDir, 4,
                (path, attrs) -> attrs.isRegularFile() && path.getFileName().toString().equals(fileName))) {
            return stream.sorted(Comparator.comparing(Path::toString)).findFirst().orElse(null);
        } catch (IOException e) {
            log.debug("Failed to scan session logs under {}", agentsDir, e);
            return null;
        }
    }

    private List<Map<String, Object>> convert(Map<String, Object> raw) {
        String role = lower(raw.get("role"));
        String content = stringValue(raw.get("content"));
        String toolCallId = stringValue(raw.get("toolCallId"));
        String id = stringValue(raw.get("id"));

        if ("assistant".equals(role) && content.contains("[tool_call:")) {
            ToolCall call = parseToolCall(content, toolCallId);
            if (call == null) return optional(textMessage(raw, role, stripToolCall(content)));
            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> prefix = textMessage(raw, role, stripToolCall(content));
            if (prefix != null) messages.add(prefix);
            messages.add(toolMessage(id, "assistant", "plugin_call", call.name, call.callId, call.arguments, null));
            return messages;
        }
        if ("tool".equals(role) && content.startsWith("[tool_result:")) {
            ToolResult result = parseToolResult(content, toolCallId);
            return List.of(toolMessage(id, "tool", "plugin_call_output", result.name, result.callId, null, result.output));
        }
        return optional(textMessage(raw, role, content));
    }

    private List<Map<String, Object>> optional(Map<String, Object> message) {
        return message == null ? List.of() : List.of(message);
    }

    private Map<String, Object> textMessage(Map<String, Object> raw, String role, String content) {
        if (role == null || role.isBlank()) role = "assistant";
        if (content == null || content.isBlank()) return null;
        Map<String, Object> message = baseMessage(raw, role);
        message.put("type", "message");
        message.put("content", List.of(Map.of("type", "text", "text", content, "status", "completed")));
        return message;
    }

    private Map<String, Object> toolMessage(String id, String role, String type, String name,
                                            String callId, Object arguments, Object output) {
        return toolMessage(id, role, type, name, callId, arguments, output, null);
    }

    private Map<String, Object> toolMessage(String id, String role, String type, String name,
                                            String callId, Object arguments, Object output,
                                            Map<String, Object> metadata) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", FrontendToolCompat.displayToolName(name != null && !name.isBlank() ? name : "unknown"));
        data.put("call_id", callId != null && !callId.isBlank() ? callId : id);
        if (arguments != null) data.put("arguments", arguments);
        if (output != null) data.put("output", output);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "data");
        content.put("object", "content");
        content.put("delta", false);
        content.put("index", null);
        content.put("status", "completed");
        content.put("data", data);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id != null && !id.isBlank() ? id : "tool_" + data.get("call_id"));
        message.put("object", "message");
        message.put("role", role);
        message.put("type", type);
        message.put("status", "completed");
        message.put("content", List.of(content));
        if (metadata != null) message.put("metadata", metadata);
        return message;
    }

    private Map<String, Object> startStateMessage(Map<String, Object> raw, String type, String role,
                                                  Map<String, Object> metadata, int index) {
        Map<String, Object> message = runtimeMessage(rawId(raw) + "_" + type + "_" + index, type, role, metadata);
        message.put("content", new ArrayList<Map<String, Object>>());
        return message;
    }

    private Map<String, Object> runtimeMessage(String id, String type, String role, Map<String, Object> metadata) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id == null || id.isBlank() ? "msg_" + UUID.randomUUID().toString().replace("-", "") : id);
        message.put("object", "message");
        message.put("role", role);
        message.put("type", type);
        message.put("status", "completed");
        message.put("metadata", metadata);
        return message;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> contentList(Map<String, Object> message) {
        return (List<Map<String, Object>>) message.get("content");
    }

    private Map<String, Object> textContent(String text) {
        Map<String, Object> content = contentBase("text");
        content.put("text", text != null ? text : "");
        return content;
    }

    private Map<String, Object> contentFromBlock(Map<String, Object> block, String blockType, String role) {
        if ("text".equals(blockType)) return textContent(stripInjectedSkillBlock(stringValue(block.get("text")), role));
        Map<String, Object> content = contentBase(blockType);
        if ("image".equals(blockType)) {
            content.put("image_url", resolveMediaUrl(block));
        } else if ("audio".equals(blockType)) {
            content.put("data", resolveMediaUrl(block));
        } else if ("video".equals(blockType)) {
            content.put("video_url", resolveMediaUrl(block));
        } else if ("file".equals(blockType)) {
            content.put("filename", stringValue(block.get("filename")));
            content.put("file_url", resolveMediaUrl(block));
        }
        return content;
    }

    private Map<String, Object> contentBase(String type) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", type);
        content.put("object", "content");
        content.put("delta", false);
        content.put("index", null);
        content.put("status", "completed");
        return content;
    }

    private String normalizedBlockType(Map<String, Object> block) {
        String type = stringValue(block.get("type"));
        if (!"data".equals(type)) return type.isBlank() ? "text" : type;
        Object source = block.get("source");
        if (source instanceof Map<?, ?> sourceMap) {
            String mediaType = stringValue(asMap(sourceMap).get("media_type"));
            if (mediaType.startsWith("image/")) return "image";
            if (mediaType.startsWith("audio/")) return "audio";
            if (mediaType.startsWith("video/")) return "video";
        }
        return "file";
    }

    private boolean isMediaBlock(String type) {
        return "image".equals(type) || "audio".equals(type) || "video".equals(type) || "file".equals(type);
    }

    private String resolveMediaUrl(Map<String, Object> block) {
        String type = stringValue(block.get("type"));
        if ("image".equals(type) && block.get("image_url") != null) return stringValue(block.get("image_url"));
        if ("audio".equals(type) && block.get("data") != null) return stringValue(block.get("data"));
        if ("video".equals(type) && block.get("video_url") != null) return stringValue(block.get("video_url"));
        if ("file".equals(type) && block.get("file_url") != null) return stringValue(block.get("file_url"));
        Object source = block.get("source");
        if (source instanceof String text) return text;
        if (source instanceof Map<?, ?> sourceMap) {
            Map<String, Object> sourceObj = asMap(sourceMap);
            if (sourceObj.get("url") != null) return resolveLocalFileUrl(stringValue(sourceObj.get("url")));
            if (sourceObj.get("data") != null) {
                String mediaType = stringValue(sourceObj.get("media_type"));
                return "data:" + (mediaType.isBlank() ? "application/octet-stream" : mediaType)
                        + ";base64," + sourceObj.get("data");
            }
        }
        return "";
    }

    private String resolveLocalFileUrl(String url) {
        if (url == null || !url.startsWith("file:")) return url;
        try {
            return Path.of(URI.create(url)).toString();
        } catch (Exception ignored) {
            return url.replaceFirst("^file:/+", "/");
        }
    }

    private Map<String, Object> stateMetadata(Map<String, Object> raw) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("original_id", raw.get("id"));
        metadata.put("original_name", raw.get("name"));
        metadata.put("metadata", raw.get("metadata") instanceof Map<?, ?> map ? asMap(map) : Map.of());
        metadata.put("timestamp", raw.get("timestamp"));
        return metadata;
    }

    private String rawId(Map<String, Object> raw) {
        String id = stringValue(raw.get("id"));
        return id.isBlank() ? "msg_" + UUID.randomUUID().toString().replace("-", "") : id;
    }

    private Object jsonStringOrRaw(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) return JsonUtils.toJson(value);
        return value;
    }

    private Object toolArgumentsJson(String toolName, Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return FrontendToolCompat.argumentsJson(toolName, JsonUtils.toJson(value));
        }
        return value;
    }

    private Map<String, Object> asMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Map<String, Object> baseMessage(Map<String, Object> raw, String role) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", stringValue(raw.get("id")));
        message.put("object", "message");
        message.put("role", role);
        message.put("status", "completed");
        message.put("metadata", Map.of("timestamp", timestamp(raw.get("timestamp"))));
        return message;
    }

    private ToolCall parseToolCall(String content, String fallbackCallId) {
        int marker = content.indexOf("[tool_call:");
        if (marker < 0) return null;
        int nameStart = marker + "[tool_call:".length();
        int argsStart = content.indexOf('(', nameStart);
        int argsEnd = content.lastIndexOf(")]");
        if (argsStart < 0 || argsEnd < argsStart) return null;

        String name = content.substring(nameStart, argsStart).trim();
        String argsText = content.substring(argsStart + 1, argsEnd).trim();
        Object args = FrontendToolCompat.argumentsJson(name, argsText);
        return new ToolCall(name, fallbackCallId, args);
    }

    private ToolResult parseToolResult(String content, String fallbackCallId) {
        int marker = content.indexOf("[tool_result:");
        int end = content.indexOf(']', marker);
        String name = "unknown";
        if (marker >= 0 && end > marker) {
            name = content.substring(marker + "[tool_result:".length(), end).trim();
        }
        String output = end >= 0 ? content.substring(end + 1).trim() : content;
        if (output.length() >= 2 && output.startsWith("\"") && output.endsWith("\"")) {
            try {
                output = JsonUtils.getMapper().readValue(output, String.class);
            } catch (IOException ignored) {
                output = output.substring(1, output.length() - 1);
            }
        }
        return new ToolResult(name, fallbackCallId, output);
    }

    private String stripToolCall(String content) {
        int marker = content.indexOf("[tool_call:");
        return marker >= 0 ? content.substring(0, marker).trim() : content;
    }

    private String stripInjectedSkillBlock(String text, String role) {
        if (!"user".equals(role) || text == null || !text.contains("<skill")) return text;
        return text.replaceFirst("(?s)\\s*<skill\\b[^>]*>.*</skill>\\s*$", "");
    }

    private String timestamp(Object value) {
        if (value instanceof Number number) {
            return Instant.ofEpochMilli((long) (number.doubleValue() * 1000)).toString();
        }
        return Instant.now().toString();
    }

    private String lower(Object value) {
        String text = stringValue(value);
        return text == null ? "" : text.toLowerCase();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record ToolCall(String name, String callId, Object arguments) {}

    private record ToolResult(String name, String callId, Object output) {}
}
