package com.melon.app.runner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import com.melon.core.util.SafePathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
 * Workspace-scoped chat registry. Matches Python QwenPaw's workspace/chats.json.
 */
@Component
public class ChatManager {

    private static final Logger log = LoggerFactory.getLogger(ChatManager.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int LARGE_TOOL_OUTPUT_CHARS = 12000;

    private final ConfigManager configManager;

    public ChatManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public List<ChatSpec> list(String agentId, String userId, String channel) {
        List<ChatSpec> chats = load(agentId);
        return chats.stream()
                .filter(chat -> userId == null || userId.isBlank() || userId.equals(chat.getUserId()))
                .filter(chat -> channel == null || channel.isBlank() || channel.equals(chat.getChannel()))
                .sorted(Comparator.comparing(ChatSpec::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public ChatSpec create(String agentId, String name, String userId, String channel, String sessionId) {
        ChatSpec spec = new ChatSpec(UUID.randomUUID().toString(), normalizeAgentId(agentId), nameOrDefault(name));
        spec.setSessionId(sessionId != null && !sessionId.isBlank() ? sessionId : newSessionId());
        spec.setUserId(valueOrDefault(userId, "default"));
        spec.setChannel(valueOrDefault(channel, "console"));
        spec.setStatus("idle");
        upsert(agentId, spec);
        log.info("Chat created: id={}, agent={}, session={}", spec.getId(), agentId, spec.getSessionId());
        return spec;
    }

    public ChatSpec get(String agentId, String chatId) {
        if (chatId == null || chatId.isBlank()) return null;
        return load(agentId).stream()
                .filter(chat -> chatId.equals(chat.getId()))
                .findFirst()
                .orElse(null);
    }

    public ChatSpec update(String agentId, ChatSpec spec) {
        if (spec == null || spec.getId() == null || spec.getId().isBlank()) {
            throw new IllegalArgumentException("ChatSpec or id is null");
        }
        spec.setUpdatedAt(Instant.now().toString());
        upsert(agentId, spec);
        return spec;
    }

    public boolean delete(String agentId, String chatId) {
        if (chatId == null || chatId.isBlank()) return false;
        List<ChatSpec> chats = new ArrayList<>(load(agentId));
        boolean removed = chats.removeIf(chat -> chatId.equals(chat.getId()));
        if (removed) save(agentId, chats);
        return removed;
    }

    public ChatSpec getOrCreateForSession(String agentId, String sessionId, String userId, String channel, String name) {
        String sid = valueOrDefault(sessionId, newSessionId());
        String uid = valueOrDefault(userId, "default");
        String ch = valueOrDefault(channel, "console");
        ChatSpec existing = load(agentId).stream()
                .filter(chat -> sid.equals(chat.getSessionId())
                        && uid.equals(chat.getUserId())
                        && ch.equals(chat.getChannel()))
                .findFirst()
                .orElse(null);
        if (existing != null) return existing;
        return create(agentId, name, uid, ch, sid);
    }

    public void setStatus(String agentId, String chatId, String status) {
        ChatSpec spec = get(agentId, chatId);
        if (spec == null) return;
        spec.setStatus(valueOrDefault(status, "idle"));
        update(agentId, spec);
    }

    public Path sessionFile(String agentId, String channel, String userId, String sessionId) {
        Path sessions = configManager.resolveWorkspaceDir(normalizeAgentId(agentId))
                .resolve("sessions")
                .resolve(valueOrDefault(channel, "console"));
        String fileName = sanitizeFileName(valueOrDefault(userId, "default") + "_" + valueOrDefault(sessionId, "default")) + ".json";
        return SafePathUtil.resolveSafe(sessions, fileName);
    }

    public void saveSessionShadow(String agentId, String channel, String userId, String sessionId, Map<String, Object> state) {
        if (state == null || state.isEmpty()) return;
        Map<String, Object> normalizedState = new LinkedHashMap<>(state);
        normalizeState(agentId, normalizedState);
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("state", normalizedState);
        agent.put("scroll", scrollIndex(agentId, sessionId, normalizedState));
        JsonUtils.save(sessionFile(agentId, channel, userId, sessionId), Map.of("agent", agent));
    }

    public void saveSessionShadowFromStateStore(String agentId, String channel, String userId, String sessionId) {
        saveSessionShadowFromStateStore(agentId, channel, userId, sessionId, List.of());
    }

    public void saveSessionShadowFromStateStore(String agentId, String channel, String userId, String sessionId,
                                                List<Map<String, Object>> prependContext) {
        Path stateFile = findStateFile(agentId, sessionId);
        Map<String, Object> state = stateFile != null ? JsonUtils.load(stateFile, MAP_TYPE) : null;
        if (state == null) state = new LinkedHashMap<>();
        mergeFrontendContext(state, prependContext);
        if (!state.isEmpty()) {
            saveSessionShadow(agentId, channel, userId, sessionId, state);
        }
    }

    public Map<String, Object> loadSessionShadow(String agentId, String channel, String userId, String sessionId) {
        Path file = sessionFile(agentId, channel, userId, sessionId);
        if (!Files.isRegularFile(file)) return Map.of();
        Map<String, Object> raw = JsonUtils.load(file, MAP_TYPE);
        return raw != null ? raw : Map.of();
    }

    private List<ChatSpec> load(String agentId) {
        importLegacyChats(agentId);
        Path file = chatsFile(agentId);
        Map<String, Object> data = JsonUtils.load(file, MAP_TYPE);
        if (data == null) return List.of();
        Object rawChats = data.get("chats");
        if (!(rawChats instanceof List<?> items)) return List.of();
        List<ChatSpec> chats = new ArrayList<>();
        for (Object item : items) {
            ChatSpec spec = JsonUtils.getMapper().convertValue(item, ChatSpec.class);
            normalize(spec, agentId);
            if (spec.getId() != null && !spec.getId().isBlank()) chats.add(spec);
        }
        return chats;
    }

    private void save(String agentId, List<ChatSpec> chats) {
        List<Map<String, Object>> payload = chats.stream().map(this::payload).toList();
        JsonUtils.save(chatsFile(agentId), Map.of("version", 1, "chats", payload));
    }

    private void upsert(String agentId, ChatSpec spec) {
        normalize(spec, agentId);
        List<ChatSpec> chats = new ArrayList<>(load(agentId));
        boolean replaced = false;
        for (int i = 0; i < chats.size(); i++) {
            if (spec.getId().equals(chats.get(i).getId())) {
                chats.set(i, spec);
                replaced = true;
                break;
            }
        }
        if (!replaced) chats.add(spec);
        save(agentId, chats);
    }

    private void importLegacyChats(String agentId) {
        Path marker = configManager.resolveWorkspaceDir(normalizeAgentId(agentId)).resolve(".legacy_chats_imported");
        if (Files.exists(marker)) return;
        Path legacyDir = configManager.resolveHomeDir().resolve("chats");
        if (!Files.isDirectory(legacyDir)) {
            writeMarker(marker);
            return;
        }
        List<ChatSpec> current = new ArrayList<>(loadWithoutLegacy(agentId));
        try (var stream = Files.list(legacyDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> importLegacyFile(path, agentId, current));
            save(agentId, current);
            writeMarker(marker);
        } catch (Exception e) {
            log.warn("Failed to import legacy chats for agent={}", agentId, e);
        }
    }

    private List<ChatSpec> loadWithoutLegacy(String agentId) {
        Path file = chatsFile(agentId);
        Map<String, Object> data = JsonUtils.load(file, MAP_TYPE);
        if (data == null || !(data.get("chats") instanceof List<?> items)) return new ArrayList<>();
        List<ChatSpec> chats = new ArrayList<>();
        for (Object item : items) {
            ChatSpec spec = JsonUtils.getMapper().convertValue(item, ChatSpec.class);
            normalize(spec, agentId);
            if (spec.getId() != null && !spec.getId().isBlank()) chats.add(spec);
        }
        return chats;
    }

    private void importLegacyFile(Path path, String agentId, List<ChatSpec> current) {
        Map<String, Object> legacy = JsonUtils.load(path, MAP_TYPE);
        if (legacy == null) return;
        String legacyAgent = stringValue(legacy.get("agent_id"));
        if (!legacyAgent.isBlank() && !normalizeAgentId(agentId).equals(legacyAgent)) return;
        String id = valueOrDefault(stringValue(legacy.get("id")), UUID.randomUUID().toString());
        boolean exists = current.stream().anyMatch(chat -> id.equals(chat.getId()));
        if (exists) return;
        ChatSpec spec = new ChatSpec(id, normalizeAgentId(agentId),
                nameOrDefault(valueOrDefault(stringValue(legacy.get("name")), stringValue(legacy.get("title")))));
        spec.setSessionId(valueOrDefault(stringValue(legacy.get("session_id")), id));
        spec.setUserId(valueOrDefault(stringValue(legacy.get("user_id")), "default"));
        spec.setChannel(valueOrDefault(stringValue(legacy.get("channel")), "console"));
        spec.setCreatedAt(valueOrDefault(stringValue(legacy.get("created_at")), Instant.now().toString()));
        spec.setUpdatedAt(valueOrDefault(stringValue(legacy.get("updated_at")), spec.getCreatedAt()));
        spec.setStatus(valueOrDefault(stringValue(legacy.get("status")), "idle"));
        spec.setSource(valueOrDefault(stringValue(legacy.get("source")), "chat"));
        current.add(spec);
    }

    private Map<String, Object> payload(ChatSpec spec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("channel", spec.getChannel());
        map.put("created_at", spec.getCreatedAt());
        map.put("id", spec.getId());
        map.put("meta", spec.getMeta());
        map.put("name", spec.getName());
        map.put("pinned", spec.isPinned());
        map.put("session_id", spec.getSessionId());
        map.put("source", spec.getSource());
        map.put("status", spec.getStatus());
        map.put("updated_at", spec.getUpdatedAt());
        map.put("user_id", spec.getUserId());
        return map;
    }

    private void normalize(ChatSpec spec, String agentId) {
        if (spec == null) return;
        String now = Instant.now().toString();
        if (spec.getId() == null || spec.getId().isBlank()) spec.setId(UUID.randomUUID().toString());
        if (spec.getAgentId() == null || spec.getAgentId().isBlank()) spec.setAgentId(normalizeAgentId(agentId));
        spec.setName(nameOrDefault(spec.getName()));
        if (spec.getSessionId() == null || spec.getSessionId().isBlank()) spec.setSessionId(newSessionId());
        spec.setUserId(valueOrDefault(spec.getUserId(), "default"));
        spec.setChannel(valueOrDefault(spec.getChannel(), "console"));
        spec.setCreatedAt(valueOrDefault(spec.getCreatedAt(), now));
        spec.setUpdatedAt(valueOrDefault(spec.getUpdatedAt(), spec.getCreatedAt()));
        spec.setStatus(valueOrDefault(spec.getStatus(), "idle"));
        spec.setSource(valueOrDefault(spec.getSource(), "chat"));
    }

    private Path chatsFile(String agentId) {
        return configManager.resolveWorkspaceDir(normalizeAgentId(agentId)).resolve("chats.json");
    }

    private Path findStateFile(String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        Path stateDir = configManager.resolveStateDir();
        List<Path> candidates = List.of(
                stateDir.resolve(normalizeAgentId(agentId)).resolve(sessionId).resolve("agent_state.json"),
                stateDir.resolve("__anon__").resolve(sessionId).resolve("agent_state.json")
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private Map<String, Object> scrollIndex(String agentId, String sessionId, Map<String, Object> state) {
        List<String> persistedIds = new ArrayList<>();
        List<String> persistedTcids = new ArrayList<>();
        Map<String, Object> seqById = new LinkedHashMap<>();
        Map<String, Object> modelTurnSeq = new LinkedHashMap<>();
        Map<String, Object> modelTurnNblk = new LinkedHashMap<>();
        int seq = 1;
        Object raw = state.get("context");
        if (raw instanceof List<?> context) {
            for (Object item : context) {
                if (!(item instanceof Map<?, ?> rawMessage)) continue;
                Map<String, Object> message = asMap(rawMessage);
                String id = valueOrDefault(stringValue(message.get("id")), UUID.randomUUID().toString().replace("-", ""));
                persistedIds.add(id);
                int blockCount = blockCount(message.get("content"));
                int start = seq;
                int end = seq + Math.max(blockCount, 1) - 1;
                seqById.put(id, List.of(start, end));
                modelTurnSeq.put(id, start);
                modelTurnNblk.put(id, blockCount);
                seq = end + 1;
                collectToolCallIds(message.get("content"), persistedTcids);
            }
        }
        Map<String, Object> scroll = new LinkedHashMap<>();
        scroll.put("persisted_ids", persistedIds);
        scroll.put("persisted_tcids", persistedTcids);
        scroll.put("synthetic_ids", List.of());
        scroll.put("seq_by_id", seqById);
        scroll.put("model_turn_seq", modelTurnSeq);
        scroll.put("model_turn_nblk", modelTurnNblk);
        scroll.put("leaf_by_id", Map.of());
        scroll.put("index", Map.of("session_id", valueOrDefault(sessionId, "default"), "agent_id", normalizeAgentId(agentId), "tiers", List.of()));
        return scroll;
    }

    @SuppressWarnings("unchecked")
    private void normalizeState(String agentId, Map<String, Object> state) {
        Object raw = state.get("context");
        if (!(raw instanceof List<?> context)) return;
        List<Object> normalized = new ArrayList<>();
        Map<String, Object> lastAssistant = null;
        for (Object item : context) {
            if (!(item instanceof Map<?, ?> rawMessage)) {
                normalized.add(item);
                continue;
            }
            Map<String, Object> message = asMap(rawMessage);
            normalizeMessage(agentId, message);
            if (isInternalContextMessage(message)) {
                continue;
            }
            if ("tool".equals(stringValue(message.get("role"))) && mergeToolMessage(lastAssistant, message)) {
                continue;
            }
            normalized.add(message);
            lastAssistant = "assistant".equals(stringValue(message.get("role"))) ? message : null;
        }
        state.put("context", normalized);
    }

    private void normalizeMessage(String agentId, Map<String, Object> message) {
        String role = stringValue(message.get("role")).toLowerCase();
        if (role.isBlank()) role = "assistant";
        message.put("role", role);
        if ("user".equals(role)) {
            message.put("name", valueOrDefault(stringValue(message.get("name")), "user"));
        } else if ("assistant".equals(role)) {
            message.put("name", normalizeAgentId(agentId));
        }
        Object content = message.get("content");
        if (!(content instanceof List<?> blocks)) return;
        List<Object> normalized = new ArrayList<>();
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> rawBlock)) {
                normalized.add(block);
                continue;
            }
            Map<String, Object> item = asMap(rawBlock);
            normalizeBlock(agentId, item);
            normalized.add(item);
        }
        message.put("content", normalized);
    }

    private void normalizeBlock(String agentId, Map<String, Object> block) {
        String type = stringValue(block.get("type"));
        if ("tool_use".equals(type)) {
            block.put("type", "tool_call");
            block.put("state", "finished");
        } else if ("tool_call".equals(type)) {
            block.putIfAbsent("state", "finished");
        } else if ("tool_result".equals(type)) {
            String state = stringValue(block.get("state")).toLowerCase();
            if (state.isBlank() || "running".equals(state) || "allowed".equals(state) || "finished".equals(state)) {
                block.put("state", "success");
            }
            maybeSpillLargeToolOutput(agentId, block);
        }
        String name = stringValue(block.get("name"));
        if (("tool_call".equals(block.get("type")) || "tool_result".equals(block.get("type")))
                && !name.isBlank()) {
            block.put("name", FrontendToolCompat.displayToolName(name));
        }
        if ("tool_call".equals(block.get("type")) && (block.get("input") instanceof Map<?, ?> || block.get("input") instanceof List<?>)) {
            block.put("input", FrontendToolCompat.argumentsJson(name, JsonUtils.toJson(block.get("input"))));
        }
    }

    @SuppressWarnings("unchecked")
    private boolean mergeToolMessage(Map<String, Object> lastAssistant, Map<String, Object> toolMessage) {
        if (lastAssistant == null) return false;
        Object rawContent = toolMessage.get("content");
        if (!(rawContent instanceof List<?> blocks)) return false;
        Object assistantRaw = lastAssistant.get("content");
        List<Object> assistantContent = assistantRaw instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
        boolean merged = false;
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> rawBlock) {
                Map<String, Object> item = asMap(rawBlock);
                if ("tool_result".equals(stringValue(item.get("type")))) {
                    assistantContent.add(item);
                    merged = true;
                }
            }
        }
        if (merged) lastAssistant.put("content", assistantContent);
        return merged;
    }

    private void maybeSpillLargeToolOutput(String agentId, Map<String, Object> block) {
        Object output = block.get("output");
        String text = output instanceof String s ? s : JsonUtils.toJson(output);
        if (text == null || text.length() <= LARGE_TOOL_OUTPUT_CHARS) return;
        try {
            Path dir = configManager.resolveWorkspaceDir(normalizeAgentId(agentId)).resolve("tool_results");
            Files.createDirectories(dir);
            String fileName = UUID.randomUUID().toString().replace("-", "") + ".txt";
            Path target = SafePathUtil.resolveSafe(dir, fileName);
            Files.writeString(target, text);
            block.put("output", List.of(Map.of(
                    "type", "text",
                    "text", "<system-reminder>Tool output was too large and has been stored at "
                            + target.toAbsolutePath().normalize() + ".</system-reminder>"
            )));
        } catch (Exception e) {
            log.warn("Failed to spill large tool output for agent={}", agentId, e);
        }
    }

    private int blockCount(Object content) {
        if (content instanceof List<?> blocks) return blocks.size();
        if (content == null) return 0;
        return 1;
    }

    private void collectToolCallIds(Object content, List<String> ids) {
        if (!(content instanceof List<?> blocks)) return;
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> rawBlock)) continue;
            String type = stringValue(rawBlock.get("type"));
            if (("tool_call".equals(type) || "tool_result".equals(type)) && rawBlock.get("id") != null) {
                ids.add(stringValue(rawBlock.get("id")));
            }
        }
    }

    private Map<String, Object> asMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private boolean isInternalContextMessage(Map<String, Object> message) {
        String name = stringValue(message.get("name"));
        if ("__compaction_summary__".equals(name)) return true;
        return "__system__".equals(name) || "__internal__".equals(name);
    }

    @SuppressWarnings("unchecked")
    private void mergeFrontendContext(Map<String, Object> state, List<Map<String, Object>> prependContext) {
        if (prependContext == null || prependContext.isEmpty()) return;
        Object raw = state.get("context");
        List<Object> context = raw instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
        for (Map<String, Object> item : prependContext) {
            String id = stringValue(item.get("id"));
            boolean exists = !id.isBlank() && context.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .anyMatch(existing -> id.equals(stringValue(existing.get("id"))));
            if (exists) continue;
            int matchingUser = findMatchingUserContext(context, item);
            if (matchingUser >= 0 && context.get(matchingUser) instanceof Map<?, ?> rawExisting) {
                Map<String, Object> existing = new LinkedHashMap<>();
                rawExisting.forEach((key, value) -> existing.put(String.valueOf(key), value));
                existing.put("content", item.get("content"));
                context.set(matchingUser, existing);
            } else {
                context.add(insertionIndexForUser(context), item);
            }
        }
        state.put("context", context);
    }

    private int insertionIndexForUser(List<Object> context) {
        int firstAssistant = -1;
        for (int i = 0; i < context.size(); i++) {
            Object item = context.get(i);
            if (!(item instanceof Map<?, ?> raw)) continue;
            String role = stringValue(raw.get("role")).toLowerCase();
            if ("user".equals(role) && !"__compaction_summary__".equals(stringValue(raw.get("name")))) {
                return context.size();
            }
            if (firstAssistant < 0 && ("assistant".equals(role) || "tool".equals(role))) {
                firstAssistant = i;
            }
        }
        return firstAssistant >= 0 ? firstAssistant : context.size();
    }

    @SuppressWarnings("unchecked")
    private int findMatchingUserContext(List<Object> context, Map<String, Object> frontendMessage) {
        String frontendText = extractText(frontendMessage.get("content"));
        if (frontendText.isBlank()) return -1;
        for (int i = context.size() - 1; i >= 0; i--) {
            Object item = context.get(i);
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> message = (Map<String, Object>) raw;
            if (!"user".equalsIgnoreCase(stringValue(message.get("role")))) continue;
            if (frontendText.equals(extractText(message.get("content")))) return i;
        }
        return -1;
    }

    private String extractText(Object content) {
        if (content instanceof String text) return text.trim();
        if (!(content instanceof List<?> blocks)) return "";
        List<String> parts = new ArrayList<>();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> raw && "text".equals(stringValue(raw.get("type")))) {
                parts.add(stringValue(raw.get("text")));
            }
        }
        return String.join("\n", parts).trim();
    }

    private void writeMarker(Path marker) {
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, Instant.now().toString());
        } catch (Exception e) {
            log.debug("Failed to write legacy import marker {}", marker, e);
        }
    }

    private static String newSessionId() {
        return System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 7);
    }

    private static String normalizeAgentId(String agentId) {
        return valueOrDefault(agentId, "default");
    }

    private static String nameOrDefault(String name) {
        return valueOrDefault(name, "New Chat");
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "default";
        String cleaned = name.replace("..", "").replace("/", "").replace("\\", "").replace("\0", "");
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        return cleaned.isBlank() ? "default" : cleaned;
    }
}
