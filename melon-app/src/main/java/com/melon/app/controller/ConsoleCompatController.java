package com.melon.app.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.app.runner.AgentRunner;
import com.melon.app.runner.ChatManager;
import com.melon.app.runner.ChatSpec;
import com.melon.app.runner.CommandDispatcher;
import com.melon.app.runner.MelonPawEnvelopeMapper;
import com.melon.app.service.ApprovalService;
import com.melon.app.service.InboxStore;
import com.melon.app.service.SkillService;
import com.melon.core.config.ConfigManager;
import com.melon.core.agent.CommandHandler;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.util.SafePathUtil;
import com.melon.tools.agent.AgentChatBridge;
import com.melon.tools.agent.SubmitToAgentTool;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.melon.core.util.ValueUtils.stringValue;
import java.util.UUID;

/**
 * melonPaw console-compatible endpoints used by the existing frontend.
 */
@RestController
@RequestMapping("/api/console")
public class ConsoleCompatController {

    private static final Logger log = LoggerFactory.getLogger(ConsoleCompatController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentRunner agentRunner;
    private final ChatManager chatManager;
    private final ApprovalService approvalService;
    private final ConfigManager configManager;
    private final CommandDispatcher commandDispatcher;
    private final SkillService skillService;
    private final MultiAgentManager multiAgentManager;
    private final InboxStore inboxStore;

    public ConsoleCompatController(AgentRunner agentRunner, ChatManager chatManager, ApprovalService approvalService,
                                   ConfigManager configManager, CommandDispatcher commandDispatcher,
                                   SkillService skillService, MultiAgentManager multiAgentManager,
                                   InboxStore inboxStore) {
        this.agentRunner = agentRunner;
        this.chatManager = chatManager;
        this.approvalService = approvalService;
        this.configManager = configManager;
        this.commandDispatcher = commandDispatcher;
        this.skillService = skillService;
        this.multiAgentManager = multiAgentManager;
        this.inboxStore = inboxStore;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        String userId = stringValue(body.get("user_id"), headerUserId != null ? headerUserId : "default");
        String sessionId = stringValue(body.get("session_id"), "default");
        String channel = stringValue(body.get("channel"), "console");
        List<Map<String, Object>> frontendContext = frontendUserMessages(agentId, body.get("input"));
        String text = modelFacingText(frontendContext);
        ChatSpec chat = chatManager.getOrCreateForSession(agentId, sessionId, userId, channel, initialTitle(agentId, text));
        String chatId = chat.getId();
        chatManager.setStatus(agentId, chatId, "running");

        String commandReply = handleCommand(agentId, text);
        if (commandReply != null) {
            MelonPawEnvelopeMapper envelope = new MelonPawEnvelopeMapper(sessionId);
            saveCommandShadow(agentId, channel, userId, sessionId, frontendContext, commandReply);
            chatManager.setStatus(agentId, chatId, "idle");
            return Flux.fromIterable(envelope.completeWithText(commandReply))
                    .doFinally(signal -> log.info("Console command handled: agent={}, session={}, chat={}, signal={}", agentId, sessionId, chatId, signal));
        }

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("agent_id", agentId);
        env.put("channel", channel);
        env.put("source", "console");
        env.put("session_id", sessionId);
        env.put("root_session_id", stringValue(body.get("root_session_id"), sessionId));
        if (body.get("env_info") instanceof Map<?, ?> envInfo) {
            for (var entry : envInfo.entrySet()) {
                env.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        if (body.get("request_context") instanceof Map<?, ?> requestContext) {
            for (var entry : requestContext.entrySet()) {
                env.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }

        List<Msg> msgs = List.of(new UserMessage(text));
        MelonPawEnvelopeMapper envelope = new MelonPawEnvelopeMapper(sessionId);
        return agentRunner.stream(agentId, msgs, userId, sessionId, env)
                .doOnSubscribe(s -> log.info("Console chat started: agent={}, user={}, session={}, chat={}", agentId, userId, sessionId, chatId))
                .flatMapIterable(envelope::translate)
                .concatWith(Flux.defer(() -> Flux.fromIterable(envelope.finish())))
                .startWith(envelope.start())
                .doFinally(signal -> {
                    chatManager.setStatus(agentId, chatId, "idle");
                    chatManager.saveSessionShadowFromStateStore(agentId, channel, userId, sessionId,
                            sessionContext(frontendContext, envelope.outputStateMessagesSnapshot()), envelope.turnUsageSnapshot());
                    log.info("Console chat finished: agent={}, session={}, chat={}, signal={}", agentId, sessionId, chatId, signal);
                })
                .onErrorResume(e -> {
                    log.error("Console chat failed: agent={}, user={}, session={}, chat={}", agentId, userId, sessionId, chatId, e);
                    chatManager.setStatus(agentId, chatId, "idle");
                    List<ServerSentEvent<String>> errorEvents = envelope.error(e);
                    chatManager.saveSessionShadowFromStateStore(agentId, channel, userId, sessionId,
                            sessionContext(frontendContext, envelope.outputStateMessagesSnapshot()), envelope.turnUsageSnapshot());
                    return Flux.fromIterable(errorEvents);
                });
    }

    @PostMapping("/chat/stop")
    public Mono<ResponseEntity<?>> stopChat(
            @RequestParam(value = "chat_id", required = false) String chatIdParam,
            @RequestParam(value = "session_id", required = false) String sessionIdParam,
            @RequestParam(value = "user_id", required = false) String userIdParam,
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body != null ? body : Map.of();
        String effectiveAgentId = stringValue(payload.get("agent_id"), agentId);
        String chatId = stringValue(payload.get("chat_id"), stringValue(chatIdParam, ""));
        ChatSpec chat = chatManager.get(effectiveAgentId, chatId);
        String chatSessionId = stringValue(chat != null ? chat.getSessionId() : null, "default");
        String chatUserId = stringValue(chat != null ? chat.getUserId() : null,
                headerUserId != null ? headerUserId : "default");
        String sessionId = stringValue(payload.get("session_id"),
                stringValue(sessionIdParam, chatSessionId));
        String userId = stringValue(payload.get("user_id"),
                stringValue(userIdParam, chatUserId));

        boolean interrupted = false;
        String detail = "";
        try {
            HarnessAgent agent = multiAgentManager.getAgent(effectiveAgentId);
            if (agent != null) {
                agent.getDelegate().interrupt(userId, sessionId);
                interrupted = true;
            } else {
                detail = "agent is not running";
            }
        } catch (Exception e) {
            log.warn("Failed to interrupt chat: agent={}, user={}, session={}, chat={}",
                    effectiveAgentId, userId, sessionId, chatId, e);
            return Mono.just(ResponseEntity.internalServerError().body(Map.of(
                    "stopped", false,
                    "interrupted", false,
                    "chat_id", chatId,
                    "agent_id", effectiveAgentId,
                    "session_id", sessionId,
                    "user_id", userId,
                    "detail", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            )));
        }

        boolean approvalCancelled = approvalService.cancelPendingApproval(sessionId);
        boolean planCancelled = approvalService.cancelPendingPlan(sessionId);
        if (!chatId.isBlank()) {
            chatManager.setStatus(effectiveAgentId, chatId, "idle");
        }
        return Mono.just(ResponseEntity.ok(Map.of(
                "stopped", true,
                "interrupted", interrupted,
                "approval_cancelled", approvalCancelled,
                "plan_cancelled", planCancelled,
                "chat_id", chatId,
                "agent_id", effectiveAgentId,
                "session_id", sessionId,
                "user_id", userId,
                "detail", detail
        )));
    }

    @PostMapping("/chat/task")
    public Mono<ResponseEntity<?>> submitChatTask(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        String userId = stringValue(body.get("user_id"), headerUserId != null ? headerUserId : "default");
        String sessionId = stringValue(body.get("session_id"), "default");
        String text = modelFacingText(frontendUserMessages(agentId, body.get("input")));
        if (text.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("detail", "input text is required")));
        }
        Map<String, Object> requestContext = objectMap(body.get("request_context"));
        String rootSessionId = stringValue(body.get("root_session_id"),
                stringValue(requestContext.get("root_session_id"), sessionId));
        String rootAgentId = stringValue(requestContext.get("root_agent_id"),
                stringValue(requestContext.get("root_agent"), userId));
        Double taskTimeout = AgentChatBridge.doubleValue(body.get("timeout"));
        AgentChatBridge.AgentRequest request = new AgentChatBridge.AgentRequest(
                agentId,
                text,
                sessionId,
                rootAgentId,
                rootSessionId,
                300,
                requestContext
        );
        SubmitToAgentTool.TaskEntry entry = SubmitToAgentTool.submit(request, taskTimeout);
        return Mono.just(ResponseEntity.ok(Map.of("task_id", entry.getTaskId())));
    }

    @GetMapping("/chat/task/{taskId}")
    public Mono<ResponseEntity<?>> getChatTask(@PathVariable String taskId) {
        Map<String, Object> payload = SubmitToAgentTool.statusPayload(taskId);
        if (payload == null) {
            return Mono.just(ResponseEntity.status(404).body(Map.of("detail", "Task not found: " + taskId)));
        }
        return Mono.just(ResponseEntity.ok(payload));
    }

    @GetMapping("/push-messages")
    public Mono<ResponseEntity<?>> pushMessages(@RequestParam(value = "session_id", required = false) String sessionId) {
        List<Map<String, Object>> pending = sessionId != null
                ? optionalList(approvalService.getPendingApproval(sessionId))
                : approvalService.getPendingApprovals();
        return Mono.just(ResponseEntity.ok(Map.of(
                "messages", List.of(),
                "pending_approvals", pending
        )));
    }

    @GetMapping("/inbox/events")
    public Mono<ResponseEntity<?>> inboxEvents(@RequestParam(defaultValue = "50") int limit,
                                               @RequestParam(defaultValue = "0") int offset,
                                               @RequestParam(value = "source_type", required = false) String sourceType,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(value = "agent_id", required = false) String agentId,
                                               @RequestParam(value = "unread_only", defaultValue = "false") boolean unreadOnly) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "events",
                inboxStore.listEvents(Math.min(Math.max(limit, 1), 500), Math.max(offset, 0),
                        sourceType, status, agentId, unreadOnly)
        )));
    }

    @PostMapping("/inbox/read")
    public Mono<ResponseEntity<?>> markInboxRead(@RequestBody(required = false) Map<String, Object> body) {
        int updated = body != null && Boolean.TRUE.equals(body.get("all"))
                ? inboxStore.markAllRead()
                : inboxStore.markRead(stringList(body != null ? body.get("event_ids") : null));
        return Mono.just(ResponseEntity.ok(Map.of("updated", updated)));
    }

    @DeleteMapping("/inbox/events/{eventId}")
    public Mono<ResponseEntity<?>> deleteInboxEvent(@PathVariable String eventId) {
        Map<String, Object> result = inboxStore.deleteEvent(eventId);
        if (!Boolean.TRUE.equals(result.get("deleted"))) {
            return Mono.just(ResponseEntity.status(404).body(Map.of("detail", "event not found")));
        }
        return Mono.just(ResponseEntity.ok(result));
    }

    @GetMapping("/inbox/traces/{runId}")
    public Mono<ResponseEntity<?>> inboxTrace(@PathVariable String runId) {
        Map<String, Object> trace = inboxStore.getTrace(runId);
        if ("not_found".equals(trace.get("status"))) {
            return Mono.just(ResponseEntity.status(404).body(Map.of("detail", "trace not found")));
        }
        return Mono.just(ResponseEntity.ok(trace));
    }

    @GetMapping("/debug/backend-logs")
    public Mono<ResponseEntity<?>> backendLogs(@RequestParam(defaultValue = "200") int lines) {
        return Mono.fromCallable(() -> ResponseEntity.ok(backendLogsPayload(lines)));
    }

    private Map<String, Object> backendLogsPayload(int lines) {
        int limit = Math.min(Math.max(lines, 0), 5000);
        Path path = backendLogPath();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", path.toString());
        payload.put("lines", limit);
        payload.put("exists", Files.isRegularFile(path));
        payload.put("updated_at", null);
        payload.put("size", 0L);
        payload.put("content", "");
        if (!Files.isRegularFile(path)) {
            return payload;
        }
        try {
            payload.put("updated_at", Files.getLastModifiedTime(path).toMillis() / 1000);
            payload.put("size", Files.size(path));
            payload.put("content", tailLog(path, limit));
        } catch (Exception e) {
            payload.put("content", "(Error reading log: " + e.getMessage() + ")");
        }
        return payload;
    }

    private Path backendLogPath() {
        String configured = firstNonBlank(
                System.getProperty("logging.file.name"),
                System.getenv("MELON_LOG_FILE"),
                System.getenv("LOG_FILE")
        );
        Path path = configured != null ? Path.of(configured) : Path.of("logs", "melon.log");
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private String tailLog(Path path, int lines) throws Exception {
        if (lines == 0) {
            return "";
        }
        long size = Files.size(path);
        if (size == 0) {
            return "";
        }
        int maxBytes = 512 * 1024;
        long offset = Math.max(0, size - maxBytes);
        byte[] buffer = new byte[(int) (size - offset)];
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(offset);
            file.readFully(buffer);
        }
        String content = new String(buffer, StandardCharsets.UTF_8);
        if (offset > 0) {
            int firstNewline = content.indexOf('\n');
            content = firstNewline >= 0 ? content.substring(firstNewline + 1) : "";
        }
        String[] all = content.split("\\R", -1);
        int end = all.length;
        if (end > 0 && all[end - 1].isEmpty()) {
            end--;
        }
        int start = Math.max(0, end - lines);
        List<String> selected = new ArrayList<>();
        for (int i = start; i < end; i++) {
            selected.add(all[i]);
        }
        return String.join("\n", selected);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> uploadCompat(
            @RequestPart("file") FilePart file,
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        String original = file.filename() != null ? file.filename() : "upload.bin";
        String stored = UUID.randomUUID().toString().replace("-", "") + "_" + safeFileName(original);
        Path mediaDir = configManager.resolveWorkspaceDir(agentId).resolve("media");
        Path target = SafePathUtil.resolveSafe(mediaDir, stored);
        return Mono.fromCallable(() -> {
                    Files.createDirectories(mediaDir);
                    return target;
                })
                .flatMap(path -> file.transferTo(path).thenReturn(path))
                .map(path -> ResponseEntity.ok(Map.of(
                        "url", path.toAbsolutePath().normalize().toString(),
                        "file_name", original,
                        "stored_name", stored,
                        "size", path.toFile().length()
                )));
    }

    private String titleFromText(String text) {
        if (text == null || text.isBlank()) return "New Chat";
        String title = text.strip().replaceAll("\\s+", " ");
        return title.length() > 30 ? title.substring(0, 30) : title;
    }

    private String initialTitle(String agentId, String text) {
        if (!autoTitleEnabled(agentId)) {
            return "New Chat";
        }
        return titleFromText(text);
    }

    private boolean autoTitleEnabled(String agentId) {
        try {
            var config = configManager.getConfig().getAgent(agentId);
            Object raw = config != null && config.getFrontendRunningConfig() != null
                    ? config.getFrontendRunningConfig().get("auto_title_config")
                    : null;
            if (raw instanceof Map<?, ?> map && map.get("enabled") != null) {
                return Boolean.parseBoolean(String.valueOf(map.get("enabled")));
            }
        } catch (Exception ignored) {
            // Use default below.
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private String extractInputText(Object input) {
        if (input == null) return "";
        if (input instanceof String s) return s;
        if (input instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object content = map.get("content");
                    parts.add(extractContentText(content));
                } else {
                    parts.add(String.valueOf(item));
                }
            }
            return String.join("\n", parts).trim();
        }
        if (input instanceof Map<?, ?> map) {
            return extractContentText(map.get("content"));
        }
        return String.valueOf(input);
    }

    private String extractContentText(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof List<?> blocks) {
            List<String> parts = new ArrayList<>();
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map && "text".equals(String.valueOf(map.get("type")))) {
                    Object text = map.get("text");
                    if (text != null) parts.add(String.valueOf(text));
                }
            }
            return String.join("\n", parts);
        }
        return String.valueOf(content);
    }

    private List<Map<String, Object>> optionalList(Map<String, Object> value) {
        return value != null ? List.of(value) : List.of();
    }

    private Map<String, Object> objectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private List<Map<String, Object>> frontendUserMessages(String agentId, Object input) {
        if (!(input instanceof List<?> list)) {
            return List.of(frontendUserMessage(normalizeContentBlocks(agentId,
                    List.of(Map.of("type", "text", "text", extractInputText(input))))));
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            if (!"user".equalsIgnoreCase(String.valueOf(map.get("role")))) continue;
            messages.add(frontendUserMessage(normalizeContentBlocks(agentId, map.get("content"))));
        }
        return messages;
    }

    private Map<String, Object> frontendUserMessage(List<Map<String, Object>> content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "user_" + UUID.randomUUID().toString().replace("-", ""));
        message.put("role", "user");
        message.put("content", content);
        message.put("metadata", Map.of());
        message.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
        return message;
    }

    private List<Map<String, Object>> normalizeContentBlocks(String agentId, Object content) {
        if (content instanceof String text) return List.of(Map.of("type", "text", "text", text));
        if (!(content instanceof List<?> blocks)) return List.of(Map.of("type", "text", "text", String.valueOf(content == null ? "" : content)));
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> raw)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            raw.forEach((key, value) -> item.put(String.valueOf(key), value));
            normalizeMediaSource(agentId, item);
            normalized.add(item);
            String path = uploadedAbsolutePath(item);
            if (!path.isBlank() && !hasUploadNotice(normalized, path)) {
                normalized.add(Map.of("type", "text", "text", "用户上传文件，已经下载到 " + path));
            }
        }
        return normalized.isEmpty() ? List.of(Map.of("type", "text", "text", "")) : normalized;
    }

    private void normalizeMediaSource(String agentId, Map<String, Object> item) {
        String type = String.valueOf(item.getOrDefault("type", "text"));
        Object url = switch (type) {
            case "image" -> item.get("image_url");
            case "video" -> item.get("video_url");
            case "audio" -> item.get("data");
            case "file" -> item.get("file_url");
            case "data" -> item.get("url");
            default -> null;
        };
        if (item.get("source") instanceof Map<?, ?> sourceRaw) {
            Map<String, Object> source = new LinkedHashMap<>();
            sourceRaw.forEach((key, value) -> source.put(String.valueOf(key), value));
            Object sourceUrl = source.get("url");
            if (sourceUrl != null) {
                source.put("url", toFileUrl(agentId, String.valueOf(sourceUrl)));
            }
            source.putIfAbsent("media_type", mediaType(type));
            if (isMediaType(type)) {
                item.put("type", "data");
                item.putIfAbsent("name", item.getOrDefault("file_name", item.getOrDefault("filename", "file")));
            }
            item.put("source", source);
            return;
        }
        if (url == null) return;
        String fileUrl = toFileUrl(agentId, String.valueOf(url));
        item.put("type", "data");
        item.put("source", Map.of("type", "url", "url", fileUrl, "media_type", mediaType(type)));
        item.putIfAbsent("name", item.getOrDefault("file_name", item.getOrDefault("filename", "file")));
        if ("file".equals(type) && !item.containsKey("filename")) {
            item.put("filename", String.valueOf(item.getOrDefault("file_name", "file")));
        }
    }

    private String modelFacingText(List<Map<String, Object>> messages) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            Object content = message.get("content");
            if (!(content instanceof List<?> blocks)) continue;
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> raw && "text".equals(String.valueOf(raw.get("type")))) {
                    String text = stringValue(raw.get("text"), "");
                    if (!text.isBlank()) parts.add(text);
                }
            }
        }
        return String.join("\n", parts).trim();
    }

    private String handleCommand(String agentId, String text) {
        if (text == null || !text.trim().startsWith("/")) return null;
        String token = text.trim().split("\\s+", 2)[0].toLowerCase();
        if (!List.of("/clear", "/compact", "/new", "/model", "/skills", "/help", "/mission").contains(token)) {
            return null;
        }
        if ("/mission".equals(token)) {
            return readWorkspaceMarkdown(agentId, "SOUL.md", "No mission file is configured.");
        }
        CommandHandler.CommandResult result = commandDispatcher.dispatch(text);
        if (result == null || result.getType() == CommandHandler.CommandType.UNKNOWN) return null;
        if (!result.isSuccess()) return result.getMessage();
        return switch (result.getAction()) {
            case SWITCH_MODEL -> switchModel(agentId, stringValue(result.getData().get("model"), ""));
            case LIST_SKILLS -> listSkills(agentId);
            case CLEAR_HISTORY -> "Conversation history is cleared for this turn. Start a new chat if you need a clean persisted session.";
            case COMPACT_CONTEXT -> "Context compaction has been requested. AgentScope will compact automatically when configured thresholds are reached.";
            case NEW_SESSION -> "New session requested. Use the new chat button to create a separate persisted session.";
            case SHOW_HELP -> result.getMessage();
            default -> result.getMessage();
        };
    }

    private String switchModel(String agentId, String model) {
        if (model == null || model.isBlank()) return "Usage: /model <provider:model>";
        var agent = configManager.getConfig().getAgent(agentId);
        if (agent == null) return "Agent not found: " + agentId;
        agent.setActiveModel(model);
        configManager.save();
        multiAgentManager.reload(agentId);
        return "Active model switched to " + model;
    }

    private String listSkills(String agentId) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> skill : skillService.listSkills(agentId)) {
            String enabled = Boolean.TRUE.equals(skill.get("enabled")) ? "enabled" : "disabled";
            String description = stringValue(skill.get("description"), "");
            lines.add("- " + stringValue(skill.get("name"), "") + " [" + enabled + "]" + (description.isBlank() ? "" : ": " + description));
        }
        return lines.isEmpty() ? "No workspace skills are installed." : String.join("\n", lines);
    }

    private String readWorkspaceMarkdown(String agentId, String fileName, String fallback) {
        try {
            Path file = configManager.resolveWorkspaceDir(agentId).resolve(fileName);
            if (Files.isRegularFile(file)) {
                String content = Files.readString(file).trim();
                if (!content.isBlank()) return content;
            }
        } catch (Exception ignored) {
            // Use fallback below.
        }
        return fallback;
    }

    private void saveCommandShadow(String agentId, String channel, String userId, String sessionId,
                                   List<Map<String, Object>> frontendContext, String reply) {
        List<Map<String, Object>> context = new ArrayList<>(frontendContext);
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("id", "assistant_" + UUID.randomUUID().toString().replace("-", ""));
        assistant.put("role", "assistant");
        assistant.put("name", agentId);
        assistant.put("content", List.of(Map.of("type", "text", "text", reply != null ? reply : "")));
        assistant.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
        context.add(assistant);
        chatManager.saveSessionShadow(agentId, channel, userId, sessionId, Map.of("context", context));
    }

    private List<Map<String, Object>> sessionContext(List<Map<String, Object>> frontendContext,
                                                     List<Map<String, Object>> outputContext) {
        List<Map<String, Object>> context = new ArrayList<>(frontendContext != null ? frontendContext : List.of());
        if (outputContext != null) context.addAll(outputContext);
        return context;
    }

    private String uploadedAbsolutePath(Map<String, Object> item) {
        Object source = item.get("source");
        if (!(source instanceof Map<?, ?> raw)) return "";
        Object url = raw.get("url");
        if (url == null) return "";
        String text = String.valueOf(url);
        if (!text.startsWith("file://")) return "";
        try {
            return Path.of(URI.create(text)).toString();
        } catch (Exception ignored) {
            return text.substring("file://".length());
        }
    }

    private boolean hasUploadNotice(List<Map<String, Object>> blocks, String path) {
        String notice = "用户上传文件，已经下载到 " + path;
        return blocks.stream().anyMatch(block ->
                "text".equals(String.valueOf(block.get("type"))) && notice.equals(String.valueOf(block.get("text"))));
    }

    private String toFileUrl(String agentId, String value) {
        if (value == null || value.isBlank() || value.startsWith("file://") || value.startsWith("http://")
                || value.startsWith("https://") || value.startsWith("data:")) {
            return value;
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = configManager.resolveWorkspaceDir(agentId).resolve("media").resolve(value);
        }
        return path.toAbsolutePath().normalize().toUri().toString();
    }

    private String mediaType(String type) {
        return switch (type) {
            case "image" -> "image/*";
            case "video" -> "video/*";
            case "audio" -> "audio/*";
            default -> "application/octet-stream";
        };
    }

    private boolean isMediaType(String type) {
        return "file".equals(type) || "image".equals(type) || "audio".equals(type) || "video".equals(type);
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) result.add(String.valueOf(item));
        }
        return result;
    }

    private String errorJson(Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return "{\"detail\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return errorJson(e);
        }
    }

    private String safeFileName(String name) {
        String cleaned = name.replace("..", "").replace("/", "").replace("\\", "").replace("\0", "");
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        return cleaned.isBlank() ? "upload.bin" : cleaned;
    }
}
