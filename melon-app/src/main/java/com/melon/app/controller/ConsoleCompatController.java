package com.melon.app.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.app.runner.AgentRunner;
import com.melon.app.runner.AgentSessionLogReader;
import com.melon.app.runner.ChatManager;
import com.melon.app.runner.ChatSpec;
import com.melon.app.runner.MelonPawEnvelopeMapper;
import com.melon.app.service.ApprovalService;
import com.melon.app.service.InboxStore;
import com.melon.app.service.SkillService;
import com.melon.core.config.ConfigManager;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.provider.ProviderManager;
import com.melon.core.util.JsonUtils;
import com.melon.core.util.SafePathUtil;
import com.melon.tools.agent.AgentChatBridge;
import com.melon.tools.agent.SubmitToAgentTool;
import com.melon.tools.shell.ExecuteShellCommandTool;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
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
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;

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
    private final SkillService skillService;
    private final MultiAgentManager multiAgentManager;
    private final InboxStore inboxStore;
    private final ProviderManager providerManager;
    private final AgentSessionLogReader sessionLogReader;

    public ConsoleCompatController(AgentRunner agentRunner, ChatManager chatManager, ApprovalService approvalService,
                                   ConfigManager configManager, SkillService skillService, MultiAgentManager multiAgentManager,
                                   InboxStore inboxStore, ProviderManager providerManager,
                                   AgentSessionLogReader sessionLogReader) {
        this.agentRunner = agentRunner;
        this.chatManager = chatManager;
        this.approvalService = approvalService;
        this.configManager = configManager;
        this.skillService = skillService;
        this.multiAgentManager = multiAgentManager;
        this.inboxStore = inboxStore;
        this.providerManager = providerManager;
        this.sessionLogReader = sessionLogReader;
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

        CommandOutcome command = handleCommand(agentId, userId, sessionId, channel, text);
        String commandReply = command.reply();
        if (commandReply != null) {
            MelonPawEnvelopeMapper envelope = new MelonPawEnvelopeMapper(sessionId);
            if (command.stateChanged()) {
                chatManager.saveSessionShadowFromStateStore(agentId, channel, userId, sessionId, List.of());
            } else {
                saveCommandShadow(agentId, channel, userId, sessionId, frontendContext, commandReply);
            }
            chatManager.setStatus(agentId, chatId, "idle");
            return Flux.fromIterable(envelope.completeWithText(commandReply))
                    .doFinally(signal -> log.info("Console command handled: agent={}, session={}, chat={}, signal={}", agentId, sessionId, chatId, signal));
        }
        if (command.modelText() != null) {
            text = command.modelText();
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
        int shellCancelled = ExecuteShellCommandTool.cancelSession(sessionId);
        if (!chatId.isBlank()) {
            chatManager.setStatus(effectiveAgentId, chatId, "idle");
        }
        return Mono.just(ResponseEntity.ok(Map.of(
                "stopped", true,
                "interrupted", interrupted,
                "approval_cancelled", approvalCancelled,
                "plan_cancelled", planCancelled,
                "shell_cancelled", shellCancelled,
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

    private CommandOutcome handleCommand(String agentId, String userId, String sessionId, String channel, String text) {
        if (text == null || !text.trim().startsWith("/")) return CommandOutcome.pass();
        String raw = text.trim();
        String body = raw.substring(1);
        int space = body.indexOf(' ');
        String token = (space >= 0 ? body.substring(0, space) : body).toLowerCase(Locale.ROOT);
        String args = space >= 0 ? body.substring(space + 1).trim() : "";
        if (token.startsWith("[") && token.endsWith("]")) {
            return dispatchSkill(agentId, channel, token.substring(1, token.length() - 1), args);
        }
        return switch (token) {
            case "help" -> CommandOutcome.reply(commandHelp());
            case "mission" -> CommandOutcome.reply(readWorkspaceMarkdown(agentId, "SOUL.md", "No mission file is configured."));
            case "skills" -> CommandOutcome.reply(listSkills(agentId, channel));
            case "clear" -> clearConversation(agentId, userId, sessionId, channel);
            case "new" -> newConversation(agentId, userId, sessionId, channel);
            case "stop" -> CommandOutcome.reply(stopConversation(agentId, userId, sessionId, args));
            case "approval" -> CommandOutcome.reply(handleApproval(agentId, sessionId, args));
            case "approve" -> CommandOutcome.reply(decideApproval(agentId, sessionId, args, true));
            case "deny" -> CommandOutcome.reply(decideApproval(agentId, sessionId, args, false));
            case "model" -> CommandOutcome.reply(handleModel(agentId, args));
            case "status" -> CommandOutcome.reply(status(agentId));
            case "restart" -> CommandOutcome.reply(restart(agentId));
            case "reload-config", "reload_config" -> CommandOutcome.reply(reloadConfig(agentId));
            case "version" -> CommandOutcome.reply("melonPaw Java 1.0.0");
            case "logs" -> CommandOutcome.reply("Runtime logs are available from the Java server process.");
            case "daemon" -> dispatchDaemon(agentId, args);
            case "history" -> CommandOutcome.reply(history(agentId, userId, channel, sessionId));
            case "message" -> CommandOutcome.reply(message(agentId, userId, channel, sessionId, args));
            case "system_prompt" -> CommandOutcome.reply(systemPrompt(agentId));
            case "compact" -> compact(agentId, userId, sessionId);
            case "compact_str" -> CommandOutcome.reply(compactSummary(agentId, userId, sessionId));
            case "summarize_status" -> CommandOutcome.reply(
                    "AgentScope Java memory maintenance has no background summary-task queue.");
            case "dream" -> CommandOutcome.reply(dream(agentId, userId, sessionId));
            case "memorize" -> CommandOutcome.reply(memorize(agentId, userId, sessionId, args));
            case "proactive" -> CommandOutcome.reply(
                    "Proactive background messages are not configured in the Java runtime.");
            case "plan" -> args.isBlank()
                    ? CommandOutcome.reply("Plan mode status is available through the agent plan tools.")
                    : CommandOutcome.pass();
            case "dump_history" -> CommandOutcome.reply(dumpHistory(agentId, userId, sessionId));
            case "load_history" -> loadHistory(agentId, userId, sessionId);
            default -> dispatchSkill(agentId, channel, token, args);
        };
    }

    private CommandOutcome dispatchDaemon(String agentId, String args) {
        if (args.isBlank()) return CommandOutcome.reply("Usage: /daemon <status|restart|reload-config|version|logs>");
        String[] parts = args.split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        return switch (name) {
            case "status" -> CommandOutcome.reply(status(agentId));
            case "restart" -> CommandOutcome.reply(restart(agentId));
            case "reload-config", "reload_config" -> CommandOutcome.reply(reloadConfig(agentId));
            case "version" -> CommandOutcome.reply("melonPaw Java 1.0.0");
            case "logs" -> CommandOutcome.reply("Runtime logs are available from the Java server process.");
            default -> CommandOutcome.reply("Unknown daemon command: " + name);
        };
    }

    private CommandOutcome dispatchSkill(String agentId, String channel, String skillName, String input) {
        Map<String, Object> skill = skillService.listSkills(agentId).stream()
                .filter(candidate -> skillName.equalsIgnoreCase(stringValue(candidate.get("name"), "")))
                .filter(candidate -> Boolean.TRUE.equals(candidate.get("enabled")))
                .filter(candidate -> skillAvailableOnChannel(candidate, channel))
                .findFirst().orElse(null);
        if (skill == null) return CommandOutcome.pass();
        String name = stringValue(skill.get("name"), skillName);
        String description = stringValue(skill.get("description"), "No description.");
        if (input.isBlank()) {
            return CommandOutcome.reply("**" + name + "**\n\n- **command**: `/" + name
                    + " <input>` to invoke\n- **description**: " + description);
        }
        String content = skillBody(stringValue(skill.get("content"), ""));
        return CommandOutcome.rewrite("Use the [" + name + "] skill to fulfill user's task: " + input + "\n\n" + content);
    }

    private boolean skillAvailableOnChannel(Map<String, Object> skill, String channel) {
        Object raw = skill.get("channels");
        if (!(raw instanceof List<?> channels) || channels.isEmpty()) return true;
        return channels.stream().map(String::valueOf)
                .anyMatch(value -> "all".equalsIgnoreCase(value) || value.equalsIgnoreCase(channel));
    }

    private String skillBody(String content) {
        if (!content.startsWith("---")) return content;
        int end = content.indexOf("\n---", 3);
        return end >= 0 ? content.substring(end + 4).strip() : content;
    }

    private CommandOutcome clearConversation(String agentId, String userId, String sessionId, String channel) {
        try {
            HarnessAgent agent = multiAgentManager.getOrCreate(agentId);
            agent.getDelegate().interrupt(userId, sessionId);
            clearAgentState(agent, userId, sessionId);
            chatManager.clearSessionShadow(agentId, channel, userId, sessionId);
            approvalService.cancelPendingApproval(sessionId);
            approvalService.cancelPendingPlan(sessionId);
            return CommandOutcome.stateChanged("History cleared.");
        } catch (Exception e) {
            log.warn("Failed to clear conversation: agent={}, session={}", agentId, sessionId, e);
            return CommandOutcome.reply("Failed to clear conversation: " + e.getMessage());
        }
    }

    private CommandOutcome newConversation(String agentId, String userId, String sessionId, String channel) {
        try {
            HarnessAgent agent = multiAgentManager.getOrCreate(agentId);
            agent.getDelegate().interrupt(userId, sessionId);
            AgentState state = agent.getDelegate().getAgentState(userId, sessionId);
            if (!state.getContext().isEmpty()) {
                RuntimeContext context = RuntimeContext.builder()
                        .userId(userId).sessionId(sessionId).agentState(state).build();
                new MemoryFlushManager(agent.workspaceFor(userId, sessionId), agent.getModel())
                        .flushMemories(context, state.getContext()).block();
            }
            clearAgentState(agent, userId, sessionId);
            chatManager.clearSessionShadow(agentId, channel, userId, sessionId);
            approvalService.cancelPendingApproval(sessionId);
            approvalService.cancelPendingPlan(sessionId);
            return CommandOutcome.stateChanged("New conversation started; prior context was written to memory.");
        } catch (Exception e) {
            log.warn("Failed to start new conversation: agent={}, session={}", agentId, sessionId, e);
            return CommandOutcome.reply("Failed to start new conversation: " + e.getMessage());
        }
    }

    private void clearAgentState(HarnessAgent agent, String userId, String sessionId) {
        AgentState state = agent.getDelegate().getAgentState(userId, sessionId);
        state.contextMutable().clear();
        state.setSummary("");
        state.getTasksContext().tasksMutable().clear();
        state.getPlanModeContext().setPlanActive(false);
        state.getPlanModeContext().setCurrentPlanFile(null);
        agent.getDelegate().saveAgentState(userId, sessionId);
    }

    private String stopConversation(String agentId, String userId, String sessionId, String args) {
        HarnessAgent agent = multiAgentManager.getAgent(agentId);
        if (agent == null) return "No active task found for this session.";
        String targetSessionId = sessionId;
        if (args != null && args.startsWith("session=")) {
            String candidate = args.substring("session=".length()).trim();
            if (!candidate.isBlank()) targetSessionId = candidate;
        }
        agent.getDelegate().interrupt(userId, targetSessionId);
        boolean approvalCancelled = approvalService.cancelPendingApproval(targetSessionId);
        return approvalCancelled ? "Task stopped and pending approval denied." : "Task stop requested.";
    }

    private String handleApproval(String agentId, String sessionId, String args) {
        if (args.startsWith("list")) {
            List<Map<String, Object>> pending = approvalService.getPendingApprovals();
            return pending.isEmpty() ? "No pending approval." : "Pending approvals:\n" + JsonUtils.toJson(pending);
        }
        if (args.startsWith("cancel")) {
            String requestId = args.substring("cancel".length()).trim();
            if (requestId.isBlank()) return "Usage: /approval cancel <request_id>";
            return decideApproval(agentId, sessionId, requestId, false).replace("Denied.", "Cancelled.");
        }
        if (args.startsWith("approve")) return decideApproval(agentId, sessionId, args.substring("approve".length()).trim(), true);
        if (args.startsWith("deny")) return decideApproval(agentId, sessionId, args.substring("deny".length()).trim(), false);
        Map<String, Object> pending = approvalService.getPendingApproval(sessionId);
        return pending == null ? "No pending approval." : "Pending approval:\n" + JsonUtils.toJson(pending);
    }

    private String decideApproval(String agentId, String sessionId, String args, boolean approved) {
        String requestId = args.isBlank() ? "" : args.split("\\s+", 2)[0];
        boolean accepted = approvalService.decidePendingApproval(sessionId, requestId, approved, agentId);
        return accepted ? (approved ? "Approved." : "Denied.") : "Approval not found.";
    }

    private String handleModel(String agentId, String args) {
        var agent = configManager.getConfig().getAgent(agentId);
        if (agent == null) return "Agent not found: " + agentId;
        if (args.isBlank()) return "Active model: " + stringValue(agent.getActiveModel(), "not configured");
        if ("list".equalsIgnoreCase(args)) {
            return providerManager.listProviders().stream().flatMap(provider -> providerManager.listModels(provider).stream()
                    .map(model -> provider + ":" + model)).sorted().collect(java.util.stream.Collectors.joining("\n"));
        }
        if ("reset".equalsIgnoreCase(args)) {
            Map<String, String> active = providerManager.getActiveModel();
            String provider = active.get("provider_id");
            String model = active.get("model");
            if (provider == null || model == null) return "No global default model is configured.";
            return switchModel(agentId, provider + ":" + model);
        }
        if (args.startsWith("info ")) {
            String model = args.substring("info ".length()).trim();
            return "Model: " + model + "\nActive: " + model.equals(agent.getActiveModel());
        }
        return switchModel(agentId, args);
    }

    private String switchModel(String agentId, String model) {
        int separator = model.indexOf(':');
        if (separator <= 0 || separator == model.length() - 1) return "Usage: /model <provider:model>";
        String provider = model.substring(0, separator);
        String name = model.substring(separator + 1);
        if (!providerManager.listProviders().contains(provider) || !providerManager.listModels(provider).contains(name)) {
            return "Model not found: " + model;
        }
        var agent = configManager.getConfig().getAgent(agentId);
        if (agent == null) return "Agent not found: " + agentId;
        agent.setActiveModel(model);
        configManager.save();
        multiAgentManager.reload(agentId);
        return "Active model switched to " + model;
    }

    private String status(String agentId) {
        var agent = configManager.getConfig().getAgent(agentId);
        if (agent == null) return "Agent not found: " + agentId;
        return "Agent: " + agentId + "\nRunning: " + multiAgentManager.isRunning(agentId)
                + "\nModel: " + stringValue(agent.getActiveModel(), "not configured");
    }

    private String restart(String agentId) {
        multiAgentManager.reload(agentId);
        return "Agent restart requested.";
    }

    private String reloadConfig(String agentId) {
        configManager.reload();
        multiAgentManager.reload(agentId);
        return "Configuration reloaded.";
    }

    private CommandOutcome compact(String agentId, String userId, String sessionId) {
        var config = configManager.getConfig().getAgent(agentId);
        if (config == null) return CommandOutcome.reply("Agent not found: " + agentId);
        if (!config.getContextCompact().isEnabled()) {
            return CommandOutcome.reply("Context compaction is disabled in the agent configuration.");
        }
        try {
            HarnessAgent agent = multiAgentManager.getOrCreate(agentId);
            AgentState state = agent.getDelegate().getAgentState(userId, sessionId);
            List<Msg> before = state.getContext();
            if (before.isEmpty()) return CommandOutcome.reply("No messages to compact.");

            RuntimeContext context = RuntimeContext.builder()
                    .userId(userId).sessionId(sessionId).agentState(state).build();
            MemoryFlushManager flush = new MemoryFlushManager(agent.workspaceFor(userId, sessionId), agent.getModel());
            ConversationCompactor compactor = new ConversationCompactor(agent.getModel(), flush);
            Optional<List<Msg>> compacted = compactor.compactIfNeeded(context, before,
                    forcedCompactionConfig(config.getContextCompact(), agent), agentId, sessionId).block();
            if (compacted == null || compacted.isEmpty()) {
                return CommandOutcome.reply("Nothing to compact; the current context already fits the retained tail.");
            }
            state.contextMutable().clear();
            state.contextMutable().addAll(compacted.get());
            agent.getDelegate().saveAgentState(userId, sessionId);
            int removed = Math.max(0, before.size() - compacted.get().size());
            return CommandOutcome.stateChanged("Context compacted: " + removed + " message(s) replaced by a summary.");
        } catch (Exception e) {
            log.warn("Manual compaction failed: agent={}, session={}", agentId, sessionId, e);
            return CommandOutcome.reply("Context compaction failed: " + e.getMessage());
        }
    }

    private CompactionConfig forcedCompactionConfig(com.melon.core.config.ContextCompactConfig config,
                                                    HarnessAgent agent) {
        int maxInputTokens = 128 * 1024;
        int keepTokens = (int) Math.max(1, Math.round(maxInputTokens * config.getReserveThresholdRatio()));
        return CompactionConfig.builder()
                .triggerMessages(1)
                .triggerTokens(1)
                .keepMessages(config.getKeepMessages())
                .keepTokens(keepTokens)
                .flushBeforeCompact(true)
                .offloadBeforeCompact(true)
                .model(agent.getModel())
                .build();
    }

    private String compactSummary(String agentId, String userId, String sessionId) {
        try {
            AgentState state = multiAgentManager.getOrCreate(agentId).getDelegate().getAgentState(userId, sessionId);
            return state.getContext().stream()
                    .filter(message -> ConversationCompactor.SUMMARY_MSG_NAME.equals(message.getName()))
                    .findFirst()
                    .map(this::messageText)
                    .filter(text -> !text.isBlank())
                    .orElse("No compressed summary is available.");
        } catch (Exception e) {
            return "Unable to read compressed summary: " + e.getMessage();
        }
    }

    private String dream(String agentId, String userId, String sessionId) {
        try {
            HarnessAgent agent = multiAgentManager.getOrCreate(agentId);
            RuntimeContext context = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
            new MemoryConsolidator(agent.workspaceFor(userId, sessionId), agent.getModel()).consolidate(context).block();
            return "Memory consolidation completed.";
        } catch (Exception e) {
            log.warn("Memory consolidation failed: agent={}, session={}", agentId, sessionId, e);
            return "Memory consolidation failed: " + e.getMessage();
        }
    }

    private String memorize(String agentId, String userId, String sessionId, String args) {
        final int count;
        try {
            count = Integer.parseInt(args.isBlank() ? "1" : args);
        } catch (NumberFormatException e) {
            return "Usage: /memorize [positive reply count]";
        }
        if (count <= 0) return "Usage: /memorize [positive reply count]";
        try {
            HarnessAgent agent = multiAgentManager.getOrCreate(agentId);
            AgentState state = agent.getDelegate().getAgentState(userId, sessionId);
            List<Msg> context = state.getContext();
            int last = -1;
            int first = -1;
            int found = 0;
            for (int index = context.size() - 1; index >= 0; index--) {
                if (context.get(index).getRole() != MsgRole.ASSISTANT) continue;
                if (last < 0) last = index;
                first = index;
                if (++found == count) break;
            }
            if (last < 0) return "No assistant replies are available to memorize.";
            for (int index = first - 1; index >= 0; index--) {
                if (context.get(index).getRole() == MsgRole.ASSISTANT) {
                    first = index + 1;
                    break;
                }
            }
            RuntimeContext runtime = RuntimeContext.builder().userId(userId).sessionId(sessionId).agentState(state).build();
            new MemoryFlushManager(agent.workspaceFor(userId, sessionId), agent.getModel())
                    .flushMemories(runtime, context.subList(first, last + 1)).block();
            return "Memory extraction completed for " + found + " assistant reply group(s).";
        } catch (Exception e) {
            log.warn("Manual memory extraction failed: agent={}, session={}", agentId, sessionId, e);
            return "Memory extraction failed: " + e.getMessage();
        }
    }

    private String dumpHistory(String agentId, String userId, String sessionId) {
        try {
            AgentState state = multiAgentManager.getOrCreate(agentId).getDelegate().getAgentState(userId, sessionId);
            Path file = SafePathUtil.resolveSafe(configManager.resolveWorkspaceDir(agentId), "debug_history.jsonl");
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (Msg message : state.getContext()) {
                    writer.write(JSON.writeValueAsString(message));
                    writer.newLine();
                }
            }
            return "History dumped: " + state.getContext().size() + " message(s) to " + file + ".";
        } catch (Exception e) {
            log.warn("History dump failed: agent={}, session={}", agentId, sessionId, e);
            return "History dump failed: " + e.getMessage();
        }
    }

    private CommandOutcome loadHistory(String agentId, String userId, String sessionId) {
        Path file = SafePathUtil.resolveSafe(configManager.resolveWorkspaceDir(agentId), "debug_history.jsonl");
        if (!Files.isRegularFile(file)) return CommandOutcome.reply("History file not found: " + file + ". Use /dump_history first.");
        try {
            List<Msg> loaded = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                if (loaded.size() >= 10_000) break;
                loaded.add(JSON.readValue(line, Msg.class));
            }
            HarnessAgent agent = multiAgentManager.getOrCreate(agentId);
            AgentState state = agent.getDelegate().getAgentState(userId, sessionId);
            state.contextMutable().clear();
            state.contextMutable().addAll(loaded);
            state.setSummary("");
            agent.getDelegate().saveAgentState(userId, sessionId);
            return CommandOutcome.stateChanged("History loaded: " + loaded.size() + " message(s) from " + file + ".");
        } catch (Exception e) {
            log.warn("History load failed: agent={}, session={}", agentId, sessionId, e);
            return CommandOutcome.reply("History load failed: " + e.getMessage());
        }
    }

    private String messageText(Msg message) {
        return message.getContent().stream()
                .filter(io.agentscope.core.message.TextBlock.class::isInstance)
                .map(io.agentscope.core.message.TextBlock.class::cast)
                .map(io.agentscope.core.message.TextBlock::getText)
                .filter(text -> text != null && !text.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String history(String agentId, String userId, String channel, String sessionId) {
        List<Map<String, Object>> messages = sessionLogReader.readFrontendMessages(agentId, userId, channel, sessionId);
        return "Messages in current session: " + messages.size() + "\nUse /message <index> to inspect one message.";
    }

    private String message(String agentId, String userId, String channel, String sessionId, String args) {
        List<Map<String, Object>> messages = sessionLogReader.readFrontendMessages(agentId, userId, channel, sessionId);
        try {
            int index = Integer.parseInt(args);
            if (index < 1 || index > messages.size()) return "Message index must be between 1 and " + messages.size() + ".";
            return JsonUtils.toJson(messages.get(index - 1));
        } catch (NumberFormatException e) {
            return "Usage: /message <index>";
        }
    }

    private String systemPrompt(String agentId) {
        var config = configManager.getConfig().getAgent(agentId);
        if (config == null) return "Agent not found: " + agentId;
        StringBuilder prompt = new StringBuilder();
        for (String fileName : config.getSystemPromptFiles()) {
            Path file = configManager.resolveWorkspaceDir(agentId).resolve(fileName);
            try {
                if (Files.isRegularFile(file)) prompt.append(Files.readString(file)).append("\n\n");
            } catch (Exception ignored) {
                // Continue with remaining prompt files.
            }
        }
        return prompt.isEmpty() ? "No system prompt is configured." : prompt.toString().strip();
    }

    private String listSkills(String agentId, String channel) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> skill : skillService.listSkills(agentId)) {
            if (!Boolean.TRUE.equals(skill.get("enabled")) || !skillAvailableOnChannel(skill, channel)) continue;
            String enabled = Boolean.TRUE.equals(skill.get("enabled")) ? "enabled" : "disabled";
            String description = stringValue(skill.get("description"), "");
            lines.add("- " + stringValue(skill.get("name"), "") + " [" + enabled + "]" + (description.isBlank() ? "" : ": " + description));
        }
        return lines.isEmpty() ? "No workspace skills are installed." : String.join("\n", lines);
    }

    private String commandHelp() {
        return "Available commands: /clear, /new, /compact, /compact_str, /history, /message, /dump_history, "
                + "/load_history, /memorize, /dream, /system_prompt, /skills, /<skill> <input>, /stop, "
                + "/approval, /approve, /deny, /model, /status, /restart, /reload-config, /version, /logs.";
    }

    private record CommandOutcome(String reply, String modelText, boolean stateChanged) {
        static CommandOutcome pass() { return new CommandOutcome(null, null, false); }
        static CommandOutcome reply(String text) { return new CommandOutcome(text, null, false); }
        static CommandOutcome stateChanged(String text) { return new CommandOutcome(text, null, true); }
        static CommandOutcome rewrite(String text) { return new CommandOutcome(null, text, false); }
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
