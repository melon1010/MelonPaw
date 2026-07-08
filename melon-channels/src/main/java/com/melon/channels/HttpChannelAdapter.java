package com.melon.channels;

import com.melon.core.util.JsonUtils;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.melon.core.util.ValueUtils.stringValue;

public class HttpChannelAdapter extends BasicChannelAdapter {

    private final ExecutorService pollingExecutor = Executors.newCachedThreadPool();
    private final Map<String, Future<?>> pollingTasks = new ConcurrentHashMap<>();
    private final Map<String, Long> telegramOffsets = new ConcurrentHashMap<>();
    private final Map<String, String> matrixSince = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();
    private final Map<String, String> slackBotUsers = new ConcurrentHashMap<>();

    public HttpChannelAdapter(String type, boolean qrcode, boolean streaming, boolean webhook) {
        super(type, true, qrcode, streaming, webhook);
    }

    @Override
    public CompletableFuture<ChannelHealth> start(String agentId,
                                                  Map<String, Object> config,
                                                  ChannelInboundDispatcher dispatcher) {
        List<String> missing = missingRequired(config != null ? config : Map.of());
        if (!missing.isEmpty()) {
            return CompletableFuture.completedFuture(health(agentId, config));
        }
        return super.start(agentId, config).thenApply(health -> {
            if (config != null && Boolean.TRUE.equals(config.get("enabled"))) {
                switch (type()) {
                    case "telegram" -> startTelegramPolling(agentId, config, dispatcher);
                    case "matrix" -> startMatrixSync(agentId, config, dispatcher);
                    case "slack" -> startSlackSocketMode(agentId, config, dispatcher);
                    case "mattermost" -> startMattermostWebSocket(agentId, config, dispatcher);
                    case "discord" -> startDiscordGateway(agentId, config, dispatcher);
                    default -> { }
                }
            }
            return health(agentId, config);
        });
    }

    @Override
    public CompletableFuture<ChannelHealth> stop(String agentId) {
        Future<?> task = pollingTasks.remove(key(agentId));
        if (task != null) task.cancel(true);
        WebSocket socket = sockets.remove(key(agentId));
        if (socket != null) socket.abort();
        return super.stop(agentId);
    }

    @Override
    public void close() {
        pollingTasks.values().forEach(task -> task.cancel(true));
        pollingTasks.clear();
        sockets.values().forEach(WebSocket::abort);
        sockets.clear();
        pollingExecutor.shutdownNow();
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        boolean enabled = Boolean.TRUE.equals(config != null ? config.get("enabled") : null);
        if (!enabled) {
            return ChannelHealth.of(type(), "stopped", false, true, true, "Channel is disabled.");
        }
        List<String> missing = missingRequired(config != null ? config : Map.of());
        if (!missing.isEmpty()) {
            return ChannelHealth.of(type(), "misconfigured", false, false, true,
                    "Missing required config: " + String.join(", ", missing));
        }
        boolean running = switch (type()) {
            case "telegram", "matrix" -> {
                Future<?> task = pollingTasks.get(key(agentId));
                yield task != null && !task.isDone() && !task.isCancelled();
            }
            case "slack", "mattermost", "discord" -> sockets.containsKey(key(agentId));
            default -> false;
        };
        if (running) {
            return ChannelHealth.of(type(), "running", true, true, true, "Channel receiver is running.");
        }
        return super.health(agentId, config);
    }

    @Override
    public ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body, Map<String, String> headers) {
        Map<String, Object> payload = body != null ? body : Map.of();
        return switch (type()) {
            case "telegram" -> parseTelegram(agentId, payload, headers);
            case "slack" -> parseSlack(agentId, payload, headers);
            case "feishu" -> parseFeishu(agentId, payload, headers);
            case "dingtalk" -> parseDingTalk(agentId, payload, headers);
            case "discord" -> parseDiscord(agentId, payload, headers);
            case "mattermost" -> parseMattermost(agentId, payload, headers);
            case "matrix" -> parseMatrix(agentId, payload, headers);
            case "onebot" -> parseOneBot(agentId, payload, headers);
            default -> super.parseWebhook(agentId, payload, headers);
        };
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        if (!Boolean.TRUE.equals(config != null ? config.get("enabled") : null)) {
            mark(message, "skipped", "channel disabled");
            return CompletableFuture.completedFuture(message);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<String> response = switch (type()) {
                    case "feishu" -> sendFeishu(message, config);
                    case "dingtalk" -> sendDingTalk(message, config);
                    case "telegram" -> sendTelegram(message, config);
                    case "slack" -> sendSlack(message, config);
                    case "discord" -> sendDiscord(message, config);
                    case "mattermost" -> sendMattermost(message, config);
                    case "matrix" -> sendMatrix(message, config);
                    default -> sendGenericWebhook(message, config);
                };
                mark(message, response.statusCode() >= 200 && response.statusCode() < 300 ? "sent" : "failed",
                        response.statusCode() + " " + response.body());
                return message;
            } catch (Exception e) {
                mark(message, "failed", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                return message;
            }
        });
    }

    @Override
    public Map<String, Object> qrcode(String agentId, Map<String, Object> config) {
        return switch (type()) {
            case "dingtalk" -> dingtalkQrcode();
            case "feishu" -> feishuQrcode(config != null ? config : Map.of());
            default -> super.qrcode(agentId, config);
        };
    }

    @Override
    public Map<String, Object> qrcodeStatus(String agentId, Map<String, Object> config, String token) {
        return switch (type()) {
            case "dingtalk" -> dingtalkQrcodeStatus(token);
            case "feishu" -> feishuQrcodeStatus(config != null ? config : Map.of(), token);
            default -> super.qrcodeStatus(agentId, config, token);
        };
    }

    private Map<String, Object> dingtalkQrcode() {
        try {
            String base = "https://oapi.dingtalk.com";
            HttpResponse<String> init = post(base + "/app/registration/init", Map.of(), Map.of("source", "MELONPAW"), Map.of());
            Map<String, Object> initBody = JsonUtils.fromJson(init.body(), Map.class);
            String nonce = initBody != null ? stringValue(initBody.get("nonce")) : "";
            if (nonce.isBlank()) return failedQrcode("DingTalk returned empty nonce: " + init.body());
            HttpResponse<String> begin = post(base + "/app/registration/begin", Map.of(), Map.of("nonce", nonce), Map.of());
            Map<String, Object> beginBody = JsonUtils.fromJson(begin.body(), Map.class);
            String deviceCode = beginBody != null ? stringValue(beginBody.get("device_code")) : "";
            String scanUrl = beginBody != null ? stringValue(beginBody.get("verification_uri_complete")) : "";
            if (deviceCode.isBlank() || scanUrl.isBlank()) return failedQrcode("DingTalk returned empty device_code or URI: " + begin.body());
            return Map.of("qrcode_img", ChannelHttpSupport.qrPngBase64(scanUrl), "poll_token", deviceCode,
                    "enabled", true, "status", "waiting", "detail", "");
        } catch (Exception e) {
            return failedQrcode(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> dingtalkQrcodeStatus(String token) {
        try {
            HttpResponse<String> response = post("https://oapi.dingtalk.com/app/registration/poll",
                    Map.of(), Map.of("device_code", token), Map.of());
            Map<String, Object> body = JsonUtils.fromJson(response.body(), Map.class);
            String status = body != null ? stringValue(body.get("status"), "WAITING") : "WAITING";
            if ("SUCCESS".equals(status)) {
                return Map.of("status", "success", "credentials", Map.of(
                        "client_id", stringValue(body.get("client_id")),
                        "client_secret", stringValue(body.get("client_secret"))));
            }
            if ("FAIL".equals(status)) return Map.of("status", "fail", "credentials", Map.of("fail_reason", stringValue(body.get("fail_reason"))));
            if ("EXPIRED".equals(status)) return Map.of("status", "expired", "credentials", Map.of());
            return Map.of("status", "waiting", "credentials", Map.of());
        } catch (Exception e) {
            return Map.of("status", "fail", "credentials", Map.of("fail_reason", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private Map<String, Object> feishuQrcode(Map<String, Object> config) {
        try {
            String endpoint = feishuAccounts(config) + "/oauth/v1/app/registration";
            HttpResponse<String> init = postForm(endpoint, Map.of("action", "init"), config);
            Map<String, Object> initBody = JsonUtils.fromJson(init.body(), Map.class);
            Object methods = initBody != null ? initBody.get("supported_auth_methods") : null;
            if (!(methods instanceof List<?> list) || !list.contains("client_secret")) {
                return failedQrcode("Feishu unsupported auth methods: " + init.body());
            }
            HttpResponse<String> begin = postForm(endpoint, Map.of(
                    "action", "begin",
                    "archetype", "PersonalAgent",
                    "auth_method", "client_secret",
                    "request_user_info", "open_id"), config);
            Map<String, Object> beginBody = JsonUtils.fromJson(begin.body(), Map.class);
            String deviceCode = beginBody != null ? stringValue(beginBody.get("device_code")) : "";
            String scanUrl = beginBody != null ? stringValue(beginBody.get("verification_uri_complete")) : "";
            if (deviceCode.isBlank() || scanUrl.isBlank()) return failedQrcode("Feishu missing device_code or QR URL: " + begin.body());
            scanUrl += scanUrl.contains("?") ? "&source=melon" : "?source=melon";
            return Map.of("qrcode_img", ChannelHttpSupport.qrPngBase64(scanUrl), "poll_token", deviceCode,
                    "enabled", true, "status", "waiting", "detail", "");
        } catch (Exception e) {
            return failedQrcode(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> feishuQrcodeStatus(Map<String, Object> config, String token) {
        try {
            HttpResponse<String> response = postForm(feishuAccounts(config) + "/oauth/v1/app/registration",
                    Map.of("action", "poll", "device_code", token), config);
            Map<String, Object> body = JsonUtils.fromJson(response.body(), Map.class);
            if (body != null && !stringValue(body.get("client_id")).isBlank() && !stringValue(body.get("client_secret")).isBlank()) {
                Map<?, ?> userInfo = map(body.get("user_info"));
                return Map.of("status", "success", "credentials", Map.of(
                        "app_id", stringValue(body.get("client_id")),
                        "app_secret", stringValue(body.get("client_secret")),
                        "open_id", stringValue(userInfo.get("open_id")),
                        "tenant_brand", stringValue(userInfo.get("tenant_brand"), stringValue(config.get("domain"), "feishu"))));
            }
            String error = body != null ? stringValue(body.get("error")) : "";
            if ("expired_token".equals(error) || "invalid_grant".equals(error)) return Map.of("status", "expired", "credentials", Map.of("fail_reason", "QR code expired"));
            if ("access_denied".equals(error)) return Map.of("status", "fail", "credentials", Map.of("fail_reason", "User denied authorization"));
            if (!error.isBlank() && !"authorization_pending".equals(error) && !"slow_down".equals(error)) {
                return Map.of("status", "fail", "credentials", Map.of("fail_reason", error));
            }
            return Map.of("status", "waiting", "credentials", Map.of());
        } catch (Exception e) {
            return Map.of("status", "fail", "credentials", Map.of("fail_reason", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private Map<String, Object> failedQrcode(String detail) {
        return Map.of("qrcode_img", "", "poll_token", "", "enabled", false, "status", "failed", "detail", detail);
    }

    private String feishuAccounts(Map<String, Object> config) {
        return "lark".equals(stringValue(config.get("domain"))) ? "https://accounts.larksuite.com" : "https://accounts.feishu.cn";
    }

    private String telegramBase(Map<String, Object> config) {
        String base = stringValue(config.get("base_url"));
        return trimSlash(base.isBlank() ? "https://api.telegram.org" : base);
    }

    private HttpResponse<String> sendTelegram(ChannelOutboundMessage message, Map<String, Object> config) throws Exception {
        String token = stringValue(config.get("bot_token"));
        String chatId = target(message, "chat_id", "chatId");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", message.getText());
        body.put("parse_mode", "Markdown");
        return post(telegramBase(config) + "/bot" + token + "/sendMessage", Map.of(), body, config);
    }

    private void startTelegramPolling(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        String key = key(agentId);
        if (pollingTasks.containsKey(key)) return;
        pollingTasks.put(key, pollingExecutor.submit(() -> telegramPollingLoop(agentId, new LinkedHashMap<>(config), dispatcher)));
    }

    private void telegramPollingLoop(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        String token = stringValue(config.get("bot_token"));
        while (!Thread.currentThread().isInterrupted()) {
            try {
                long offset = telegramOffsets.getOrDefault(key(agentId), 0L);
                String url = telegramBase(config) + "/bot" + token + "/getUpdates?timeout=25";
                if (offset > 0) url += "&offset=" + offset;
                HttpResponse<String> response = get(url, config);
                Map<String, Object> body = JsonUtils.fromJson(response.body(), Map.class);
                Object raw = body != null ? body.get("result") : null;
                if (raw instanceof List<?> updates) {
                    for (Object update : updates) {
                        if (!(update instanceof Map<?, ?> rawMap)) continue;
                        Map<String, Object> payload = new LinkedHashMap<>();
                        rawMap.forEach((k, v) -> payload.put(String.valueOf(k), v));
                        long updateId = longValue(payload.get("update_id"));
                        if (updateId >= 0) telegramOffsets.put(key(agentId), updateId + 1);
                        if (!acceptTelegramUpdate(payload, config)) continue;
                        ChannelInboundMessage inbound = parseTelegram(agentId, payload, Map.of());
                        dispatcher.dispatch(inbound, 20);
                    }
                }
            } catch (Exception e) {
                sleep(1500);
            }
        }
    }

    private void startMatrixSync(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        String key = key(agentId);
        if (pollingTasks.containsKey(key)) return;
        pollingTasks.put(key, pollingExecutor.submit(() -> matrixSyncLoop(agentId, new LinkedHashMap<>(config), dispatcher)));
    }

    private void matrixSyncLoop(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        String base = trimSlash(stringValue(config.get("homeserver")));
        String self = stringValue(config.get("user_id"));
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String since = matrixSince.getOrDefault(key(agentId), "");
                String url = base + "/_matrix/client/v3/sync?timeout=" + stringValue(config.get("sync_timeout_ms"), "30000");
                if (!since.isBlank()) url += "&since=" + encode(since);
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(45))
                        .header("Authorization", "Bearer " + stringValue(config.get("access_token")))
                        .GET();
                HttpResponse<String> response = client(config).send(builder.build(), HttpResponse.BodyHandlers.ofString());
                Map<String, Object> body = JsonUtils.fromJson(response.body(), Map.class);
                if (body == null) continue;
                String next = stringValue(body.get("next_batch"));
                if (!next.isBlank()) matrixSince.put(key(agentId), next);
                Map<?, ?> rooms = map(body.get("rooms"));
                Map<?, ?> joined = map(rooms.get("join"));
                for (var entry : joined.entrySet()) {
                    String roomId = String.valueOf(entry.getKey());
                    Map<?, ?> room = map(entry.getValue());
                    Map<?, ?> timeline = map(room.get("timeline"));
                    for (Object item : list(timeline.get("events"))) {
                        Map<?, ?> event = map(item);
                        if (!"m.room.message".equals(stringValue(event.get("type")))) continue;
                        if (self.equals(stringValue(event.get("sender")))) continue;
                        Map<String, Object> payload = copyMap(event);
                        payload.put("room_id", roomId);
                        ChannelInboundMessage inbound = parseMatrix(agentId, payload, Map.of());
                        if (!inbound.getContent().isBlank() || !inbound.getAttachments().isEmpty()) {
                            dispatcher.dispatch(inbound, 20);
                        }
                    }
                }
            } catch (Exception e) {
                sleep(3000);
            }
        }
    }

    private void startSlackSocketMode(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        String key = key(agentId);
        if (sockets.containsKey(key)) return;
        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> response = post("https://slack.com/api/apps.connections.open",
                        Map.of("Authorization", "Bearer " + stringValue(config.get("app_token"))), Map.of(), config);
                Map<String, Object> body = JsonUtils.fromJson(response.body(), Map.class);
                String url = body != null ? stringValue(body.get("url")) : "";
                if (url.isBlank()) throw new IllegalStateException("Slack Socket Mode URL is empty: " + response.body());
                resolveSlackBotUser(agentId, config);
                WebSocket socket = client(config).newWebSocketBuilder()
                        .buildAsync(URI.create(url), new SlackSocketListener(agentId, config, dispatcher))
                        .join();
                sockets.put(key, socket);
            } catch (Exception ignored) {
                sockets.remove(key);
            }
        }, pollingExecutor);
    }

    private void startMattermostWebSocket(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        String key = key(agentId);
        if (sockets.containsKey(key)) return;
        CompletableFuture.runAsync(() -> {
            try {
                String base = trimSlash(stringValue(config.get("url")));
                String wsUrl = base.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://") + "/api/v4/websocket";
                WebSocket socket = client(config).newWebSocketBuilder()
                        .buildAsync(URI.create(wsUrl), new MattermostSocketListener(agentId, config, dispatcher))
                        .join();
                sockets.put(key, socket);
            } catch (Exception ignored) {
                sockets.remove(key);
            }
        }, pollingExecutor);
    }

    private void startDiscordGateway(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        String key = key(agentId);
        if (sockets.containsKey(key)) return;
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("https://discord.com/api/v10/gateway/bot"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bot " + stringValue(config.get("bot_token")))
                        .GET()
                        .build();
                HttpResponse<String> response = client(config).send(request, HttpResponse.BodyHandlers.ofString());
                Map<String, Object> body = JsonUtils.fromJson(response.body(), Map.class);
                String url = body != null ? stringValue(body.get("url")) : "";
                if (url.isBlank()) throw new IllegalStateException("Discord gateway URL is empty: " + response.body());
                WebSocket socket = client(config).newWebSocketBuilder()
                        .buildAsync(URI.create(url + "/?v=10&encoding=json"), new DiscordGatewayListener(agentId, config, dispatcher))
                        .join();
                sockets.put(key, socket);
            } catch (Exception ignored) {
                sockets.remove(key);
            }
        }, pollingExecutor);
    }

    private boolean acceptTelegramUpdate(Map<String, Object> payload, Map<String, Object> config) {
        Map<?, ?> message = firstMap(payload, "message", "edited_message", "channel_post");
        Map<?, ?> from = map(message.get("from"));
        boolean isBot = Boolean.TRUE.equals(from.get("is_bot"));
        return !isBot || Boolean.TRUE.equals(config.get("accept_bot_messages"));
    }

    private HttpResponse<String> sendFeishu(ChannelOutboundMessage message, Map<String, Object> config) throws Exception {
        String domain = "lark".equals(stringValue(config.get("domain"))) ? "https://open.larksuite.com" : "https://open.feishu.cn";
        HttpResponse<String> tokenResponse = post(domain + "/open-apis/auth/v3/tenant_access_token/internal",
                Map.of(),
                Map.of("app_id", stringValue(config.get("app_id")), "app_secret", stringValue(config.get("app_secret"))),
                config);
        Map<String, Object> tokenBody = JsonUtils.fromJson(tokenResponse.body(), Map.class);
        String token = tokenBody != null ? stringValue(tokenBody.get("tenant_access_token")) : "";
        if (token.isBlank()) throw new IllegalStateException("Feishu tenant_access_token is empty: " + tokenResponse.body());
        String chatId = target(message, "chat_id", "chatId", "open_chat_id");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receive_id", chatId);
        body.put("msg_type", "text");
        body.put("content", JsonUtils.toJson(Map.of("text", message.getText())));
        return post(domain + "/open-apis/im/v1/messages?receive_id_type=chat_id",
                Map.of("Authorization", "Bearer " + token), body, config);
    }

    private HttpResponse<String> sendDingTalk(ChannelOutboundMessage message, Map<String, Object> config) throws Exception {
        String url = meta(message, "sessionWebhook", "reply_url", "webhook_url");
        if (url.isBlank()) url = first(config, "webhook_url", "outgoing_webhook", "reply_url");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "markdown");
        body.put("markdown", Map.of("title", "melonPaw", "text", message.getText()));
        return post(url, Map.of(), body, config);
    }

    private HttpResponse<String> sendSlack(ChannelOutboundMessage message, Map<String, Object> config) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", target(message, "channel", "channel_id"));
        body.put("text", message.getText());
        String thread = meta(message, "thread_ts", "ts");
        if (!thread.isBlank()) body.put("thread_ts", thread);
        return post("https://slack.com/api/chat.postMessage",
                Map.of("Authorization", "Bearer " + stringValue(config.get("bot_token"))), body, config);
    }

    private HttpResponse<String> sendDiscord(ChannelOutboundMessage message, Map<String, Object> config) throws Exception {
        String channelId = target(message, "channel_id", "channel");
        return post("https://discord.com/api/v10/channels/" + encode(channelId) + "/messages",
                Map.of("Authorization", "Bot " + stringValue(config.get("bot_token"))),
                Map.of("content", message.getText()), config);
    }

    private HttpResponse<String> sendMattermost(ChannelOutboundMessage message, Map<String, Object> config) throws Exception {
        String base = trimSlash(stringValue(config.get("url")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel_id", target(message, "channel_id", "channel"));
        body.put("message", message.getText());
        return post(base + "/api/v4/posts",
                Map.of("Authorization", "Bearer " + stringValue(config.get("bot_token"))), body, config);
    }

    private HttpResponse<String> sendMatrix(ChannelOutboundMessage message, Map<String, Object> config) throws Exception {
        String base = trimSlash(stringValue(config.get("homeserver")));
        String roomId = target(message, "room_id", "roomId", "room");
        String txn = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "m.text");
        body.put("body", message.getText());
        body.put("format", "org.matrix.custom.html");
        body.put("formatted_body", message.getText());
        return put(base + "/_matrix/client/v3/rooms/" + encode(roomId) + "/send/m.room.message/" + txn,
                Map.of("Authorization", "Bearer " + stringValue(config.get("access_token"))), body, config);
    }

    private HttpResponse<String> sendGenericWebhook(ChannelOutboundMessage message, Map<String, Object> config) throws Exception {
        String url = meta(message, "reply_url", "webhook_url", "response_url", "sessionWebhook");
        if (url.isBlank()) url = first(config, "webhook_url", "outgoing_webhook", "reply_url", "base_url");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", message.getChannel());
        body.put("user_id", message.getUserId());
        body.put("session_id", message.getSessionId());
        body.put("text", message.getText());
        body.put("format", message.getFormat());
        body.put("meta", message.getMeta());
        return post(url, Map.of(), body, config);
    }

    private ChannelInboundMessage parseTelegram(String agentId, Map<String, Object> payload, Map<String, String> headers) {
        Map<?, ?> message = firstMap(payload, "message", "edited_message", "channel_post");
        Map<?, ?> chat = map(message.get("chat"));
        Map<?, ?> from = map(message.get("from"));
        String chatId = stringValue(chat.get("id"));
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(stringValue(from.get("id"), chatId));
        inbound.setSessionId(chatId);
        inbound.setContent(stringValue(message.get("text"), stringValue(message.get("caption"))));
        inbound.setAttachments(telegramAttachments(message));
        Map<String, Object> meta = copy(payload);
        String chatType = stringValue(chat.get("type"));
        boolean isGroup = "group".equals(chatType) || "supergroup".equals(chatType) || "channel".equals(chatType);
        meta.put("is_group", isGroup);
        meta.put("telegram_chat_id", chatId);
        meta.put("telegram_user_id", inbound.getUserId());
        MentionFlags flags = telegramMentionFlags(message);
        if (flags.hasBotCommand()) meta.put("has_bot_command", true);
        if (flags.botMentioned()) meta.put("bot_mentioned", true);
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress("chat", chatId, Map.of("chat_id", chatId, "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    private ChannelInboundMessage parseSlack(String agentId, Map<String, Object> payload, Map<String, String> headers) {
        Map<?, ?> event = map(payload.get("event"));
        String channel = stringValue(event.get("channel"), stringValue(payload.get("channel_id")));
        String user = stringValue(event.get("user"), stringValue(payload.get("user_id")));
        String thread = stringValue(event.get("thread_ts"), stringValue(event.get("ts")));
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(user);
        boolean direct = channel.startsWith("D");
        inbound.setSessionId(slackSession(channel, thread, user, direct));
        String text = stringValue(event.get("text"), stringValue(payload.get("text")));
        String prefix = stringValue(payload.get("bot_prefix"));
        if (!prefix.isBlank() && text.startsWith(prefix)) {
            text = text.substring(prefix.length()).trim();
        }
        text = text.replaceFirst("^<@\\w+>\\s+(?=/)", "").trim();
        inbound.setContent(text);
        inbound.setAttachments(mapList(event.get("files")));
        Map<String, Object> meta = copy(payload);
        meta.put("slack_channel_id", channel);
        meta.put("slack_thread_ts", thread);
        meta.put("slack_message_ts", stringValue(event.get("ts")));
        meta.put("slack_user_id", user);
        meta.put("is_group", !direct);
        String botUser = stringValue(payload.get("bot_user_id"));
        if (!botUser.isBlank() && stringValue(event.get("text")).contains("<@" + botUser + ">")) {
            meta.put("bot_mentioned", true);
        }
        if (Boolean.TRUE.equals(payload.get("slack_is_slash_command")) || text.startsWith("/")) {
            meta.put("has_bot_command", true);
        }
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress("channel", channel, Map.of("channel", channel, "thread_ts", thread, "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    private ChannelInboundMessage parseFeishu(String agentId, Map<String, Object> payload, Map<String, String> headers) {
        Map<?, ?> event = map(payload.get("event"));
        Map<?, ?> message = map(event.get("message"));
        Map<?, ?> sender = map(event.get("sender"));
        Map<?, ?> senderId = map(sender.get("sender_id"));
        String chatId = stringValue(message.get("chat_id"));
        String content = stringValue(message.get("content"));
        Map<?, ?> contentMap = JsonUtils.fromJson(content, Map.class);
        String text = contentMap != null ? stringValue(contentMap.get("text"), content) : content;
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(stringValue(senderId.get("open_id"), stringValue(senderId.get("user_id"))));
        String chatType = stringValue(message.get("chat_type"), stringValue(message.get("chatType"), "p2p"));
        String appId = first(payload, "app_id", "appId");
        inbound.setSessionId("group".equals(chatType) && !chatId.isBlank()
                ? (appId.length() >= 4 ? appId.substring(appId.length() - 4) + "_" : "") + shortId(chatId)
                : shortId(inbound.getUserId().isBlank() ? chatId : inbound.getUserId()));
        inbound.setContent(text);
        Map<String, Object> meta = copy(payload);
        meta.put("feishu_chat_id", chatId);
        meta.put("feishu_chat_type", chatType);
        meta.put("feishu_sender_id", inbound.getUserId());
        meta.put("feishu_message_id", stringValue(message.get("message_id"), stringValue(message.get("messageId"))));
        meta.put("is_group", "group".equals(chatType));
        meta.put("feishu_receive_id", "group".equals(chatType) ? chatId : inbound.getUserId());
        meta.put("feishu_receive_id_type", "group".equals(chatType) ? "chat_id" : "open_id");
        if (feishuMentionsBot(content, message)) meta.put("bot_mentioned", true);
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress("chat", "group".equals(chatType) ? chatId : inbound.getUserId(),
                Map.of("chat_id", chatId, "open_id", inbound.getUserId(), "receive_id", meta.get("feishu_receive_id"),
                        "receive_id_type", meta.get("feishu_receive_id_type"), "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    private ChannelInboundMessage parseDingTalk(String agentId, Map<String, Object> payload, Map<String, String> headers) {
        String sessionWebhook = first(payload, "sessionWebhook", "session_webhook", "reply_url");
        String conversation = first(payload, "conversationId", "conversation_id", "chat_id");
        String sender = first(payload, "senderStaffId", "senderId", "sender_id", "user_id");
        String conversationType = dingtalkConversationType(payload);
        String senderName = first(payload, "senderNick", "sender_nick", "senderName", "user_name");
        String displaySender = displaySender(senderName, sender);
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(displaySender);
        inbound.setSessionId(shortId(conversation));
        inbound.setContent(dingtalkText(payload));
        Map<String, Object> meta = copy(payload);
        meta.put("conversation_id", conversation);
        meta.put("conversation_type", conversationType);
        meta.put("sender_staff_id", sender);
        meta.put("sender_id", sender);
        meta.put("user_name", senderName);
        meta.put("is_group", "group".equals(conversationType));
        if (Boolean.TRUE.equals(payload.get("isInAtList")) || Boolean.TRUE.equals(payload.get("is_in_at_list"))) {
            meta.put("bot_mentioned", true);
        }
        if (inbound.getContent().startsWith("/")) meta.put("has_bot_command", true);
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress("conversation", conversation,
                Map.of("sessionWebhook", sessionWebhook, "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    private ChannelInboundMessage parseDiscord(String agentId, Map<String, Object> payload, Map<String, String> headers) {
        Map<?, ?> author = map(payload.get("author"));
        String channel = first(payload, "channel_id", "channel");
        ChannelInboundMessage inbound = super.parseWebhook(agentId, payload, headers);
        inbound.setUserId(stringValue(author.get("id"), inbound.getUserId()));
        inbound.setSessionId(channel);
        inbound.setContent(first(payload, "content", "text"));
        inbound.setAttachments(mapList(payload.get("attachments")));
        Map<String, Object> meta = copy(payload);
        meta.put("is_group", !stringValue(payload.get("guild_id")).isBlank());
        if (!list(payload.get("mentions")).isEmpty()) meta.put("bot_mentioned", true);
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress("channel", channel, Map.of("channel_id", channel, "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    private ChannelInboundMessage parseMattermost(String agentId, Map<String, Object> payload, Map<String, String> headers) {
        ChannelInboundMessage inbound = super.parseWebhook(agentId, payload, headers);
        String channel = first(payload, "channel_id", "channel");
        inbound.setUserId(first(payload, "user_id", "user_name"));
        inbound.setSessionId(channel);
        inbound.setContent(first(payload, "text", "message"));
        inbound.setAttachments(mattermostAttachments(payload));
        Map<String, Object> meta = copy(payload);
        meta.put("is_group", true);
        if (Boolean.TRUE.equals(payload.get("mentions_bot")) || stringValue(payload.get("text")).contains("@")) {
            meta.put("bot_mentioned", Boolean.TRUE.equals(payload.get("mentions_bot")));
        }
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress("channel", channel, Map.of("channel_id", channel, "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    private ChannelInboundMessage parseMatrix(String agentId, Map<String, Object> payload, Map<String, String> headers) {
        Map<?, ?> content = map(payload.get("content"));
        String room = first(payload, "room_id", "room");
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(first(payload, "sender", "user_id"));
        inbound.setSessionId(room);
        inbound.setContent(stringValue(content.get("body"), first(payload, "text", "message")));
        if (!stringValue(content.get("url")).isBlank()) {
            Map<String, Object> attachment = copyMap(content);
            Map<?, ?> info = map(content.get("info"));
            if (!stringValue(info.get("mimetype")).isBlank()) {
                attachment.put("mime_type", stringValue(info.get("mimetype")));
            }
            attachment.putIfAbsent("filename", stringValue(content.get("body"), "matrix-file"));
            inbound.setAttachments(List.of(attachment));
        }
        Map<String, Object> meta = copy(payload);
        meta.put("is_group", true);
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress("room", room, Map.of("room_id", room, "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    private ChannelInboundMessage parseOneBot(String agentId, Map<String, Object> payload, Map<String, String> headers) {
        String groupId = stringValue(payload.get("group_id"));
        String userId = stringValue(payload.get("user_id"));
        String session = !groupId.isBlank() ? "group:" + groupId : "private:" + userId;
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(userId);
        inbound.setSessionId(session);
        inbound.setContent(first(payload, "raw_message", "message", "text"));
        Map<String, Object> meta = copy(payload);
        meta.put("is_group", !groupId.isBlank());
        meta.put("bot_mentioned", onebotMentioned(payload));
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress(groupId.isBlank() ? "private" : "group",
                groupId.isBlank() ? userId : groupId,
                Map.of("group_id", groupId, "user_id", userId, "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    private List<String> missingRequired(Map<String, Object> config) {
        return switch (type()) {
            case "feishu" -> missing(config, "app_id", "app_secret");
            case "telegram" -> missing(config, "bot_token");
            case "slack" -> missing(config, "bot_token", "app_token");
            case "discord" -> missing(config, "bot_token");
            case "mattermost" -> missing(config, "url", "bot_token");
            case "matrix" -> missing(config, "homeserver", "user_id", "access_token");
            case "dingtalk" -> List.of();
            default -> first(config, "webhook_url", "outgoing_webhook", "reply_url", "base_url").isBlank()
                    ? List.of("webhook_url")
                    : List.of();
        };
    }

    private void resolveSlackBotUser(String agentId, Map<String, Object> config) {
        String configured = stringValue(config.get("bot_user_id"));
        if (!configured.isBlank()) {
            slackBotUsers.put(key(agentId), configured);
            config.put("bot_user_id", configured);
            return;
        }
        try {
            HttpResponse<String> response = post("https://slack.com/api/auth.test",
                    Map.of("Authorization", "Bearer " + stringValue(config.get("bot_token"))), Map.of(), config);
            Map<String, Object> body = JsonUtils.fromJson(response.body(), Map.class);
            String userId = body != null ? stringValue(body.get("user_id")) : "";
            if (!userId.isBlank()) {
                slackBotUsers.put(key(agentId), userId);
                config.put("bot_user_id", userId);
            }
        } catch (Exception ignored) {
        }
    }

    private MentionFlags telegramMentionFlags(Map<?, ?> message) {
        boolean command = false;
        boolean mention = false;
        for (String key : List.of("entities", "caption_entities")) {
            for (Object item : list(message.get(key))) {
                Map<?, ?> entity = map(item);
                String type = stringValue(entity.get("type"));
                if ("bot_command".equals(type)) command = true;
                if ("mention".equals(type) || "text_mention".equals(type)) mention = true;
            }
        }
        return new MentionFlags(command, mention);
    }

    private String slackSession(String channel, String thread, String user, boolean direct) {
        if (!thread.isBlank()) return "slack:thread:" + channel + ":" + thread;
        if (direct && !user.isBlank()) return "slack:dm:" + user;
        return "slack:ch:" + channel;
    }

    private boolean feishuMentionsBot(String content, Map<?, ?> message) {
        if (content != null && content.contains("@_all")) return true;
        return !list(message.get("mentions")).isEmpty();
    }

    private String dingtalkConversationType(Map<String, Object> payload) {
        String raw = first(payload, "conversationType", "conversation_type");
        return "2".equals(raw) || "group".equalsIgnoreCase(raw) ? "group" : "dm";
    }

    private String dingtalkText(Map<String, Object> payload) {
        Object text = payload.get("text");
        if (text instanceof Map<?, ?> map) {
            String content = stringValue(map.get("content"));
            if (!content.isBlank()) return content;
        }
        return first(payload, "content", "message", "text");
    }

    private String displaySender(String nickname, String senderId) {
        String suffix = senderId != null && senderId.length() >= 4 ? senderId.substring(senderId.length() - 4) : stringValue(senderId, "????");
        return (nickname == null || nickname.isBlank() ? "unknown" : nickname) + "#" + suffix;
    }

    private boolean onebotMentioned(Map<String, Object> payload) {
        Object raw = payload.get("message");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Map<?, ?> segment = map(item);
                if ("at".equals(stringValue(segment.get("type")))) return true;
            }
        }
        return stringValue(payload.get("raw_message"), stringValue(payload.get("message"))).contains("[CQ:at");
    }

    private String shortId(String value) {
        String text = stringValue(value);
        return text.length() >= 8 ? text.substring(text.length() - 8) : text;
    }

    private record MentionFlags(boolean hasBotCommand, boolean botMentioned) {
    }

    private List<String> missing(Map<String, Object> config, String... keys) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (String key : keys) {
            if (stringValue(config.get(key)).isBlank()) result.add(key);
        }
        return result;
    }

    private List<Map<String, Object>> telegramAttachments(Map<?, ?> message) {
        java.util.ArrayList<Map<String, Object>> result = new java.util.ArrayList<>();
        Object document = message.get("document");
        if (document instanceof Map<?, ?> map) result.add(telegramFile(map, "application/octet-stream"));
        Object video = message.get("video");
        if (video instanceof Map<?, ?> map) result.add(telegramFile(map, "video/*"));
        Object audio = message.get("audio");
        if (audio instanceof Map<?, ?> map) result.add(telegramFile(map, "audio/*"));
        Object voice = message.get("voice");
        if (voice instanceof Map<?, ?> map) result.add(telegramFile(map, "audio/*"));
        Object photo = message.get("photo");
        if (photo instanceof List<?> photos && !photos.isEmpty()) {
            Object best = photos.get(photos.size() - 1);
            if (best instanceof Map<?, ?> map) result.add(telegramFile(map, "image/*"));
        }
        return result;
    }

    private Map<String, Object> telegramFile(Map<?, ?> raw, String mediaType) {
        Map<String, Object> file = new LinkedHashMap<>();
        raw.forEach((key, value) -> file.put(String.valueOf(key), value));
        file.put("media_type", mediaType);
        String name = first(file, "file_name", "filename", "name");
        if (name.isBlank()) file.put("filename", "telegram-file");
        return file;
    }

    private List<Map<String, Object>> mattermostAttachments(Map<String, Object> payload) {
        java.util.ArrayList<Map<String, Object>> result = new java.util.ArrayList<>(mapList(payload.get("files")));
        Object fileIds = payload.get("file_ids");
        if (fileIds instanceof List<?> ids) {
            for (Object id : ids) {
                String value = stringValue(id);
                if (!value.isBlank()) result.add(Map.of("file_id", value, "filename", "mattermost-file"));
            }
        }
        return result;
    }

    private List<Map<String, Object>> mapList(Object raw) {
        java.util.ArrayList<Map<String, Object>> result = new java.util.ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(copyMap(map));
                }
            }
        }
        return result;
    }

    private Map<String, Object> copyMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private HttpResponse<String> post(String url, Map<String, String> headers, Object body, Map<String, Object> config) throws Exception {
        return request("POST", url, headers, body, config);
    }

    private HttpResponse<String> put(String url, Map<String, String> headers, Object body, Map<String, Object> config) throws Exception {
        return request("PUT", url, headers, body, config);
    }

    private HttpResponse<String> postForm(String url, Map<String, String> body, Map<String, Object> config) throws Exception {
        String form = body.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        return client(config).send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String url, Map<String, Object> config) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(35))
                .GET();
        return client(config).send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> request(String method, String url, Map<String, String> headers, Object body, Map<String, Object> config) throws Exception {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Missing outbound URL for channel " + type());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(body)));
        if (headers != null) headers.forEach(builder::header);
        return client(config).send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient client(Map<String, Object> config) {
        String proxy = first(config, "http_proxy", "proxy");
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        if (!proxy.isBlank()) {
            URI uri = URI.create(proxy);
            builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), uri.getPort())));
        }
        return builder.build();
    }

    private String target(ChannelOutboundMessage message, String... metaKeys) {
        String target = message.getTo() != null ? stringValue(message.getTo().getId()) : "";
        if (!target.isBlank() && !"default".equals(target)) return target;
        if (message.getTo() != null && message.getTo().getExtra() != null) {
            for (String key : metaKeys) {
                String value = stringValue(message.getTo().getExtra().get(key));
                if (!value.isBlank()) return value;
            }
        }
        String meta = meta(message, metaKeys);
        if (!meta.isBlank()) return meta;
        throw new IllegalArgumentException("Missing target for channel " + type());
    }

    private String meta(ChannelOutboundMessage message, String... keys) {
        Object raw = message.getMeta() != null ? message.getMeta().get("channel_meta") : null;
        Map<?, ?> meta = map(raw);
        for (String key : keys) {
            String value = stringValue(meta.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String first(Map<String, Object> map, String... keys) {
        if (map == null) return "";
        for (String key : keys) {
            String value = stringValue(map.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private Map<?, ?> firstMap(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Map<?, ?> result = map(map.get(key));
            if (!result.isEmpty()) return result;
        }
        return Map.of();
    }

    private Map<?, ?> map(Object raw) {
        return raw instanceof Map<?, ?> map ? map : Map.of();
    }

    private List<?> list(Object raw) {
        return raw instanceof List<?> list ? list : List.of();
    }

    private Map<String, Object> copy(Map<String, Object> payload) {
        Map<String, Object> copy = new LinkedHashMap<>();
        payload.forEach((key, value) -> copy.put(key, value));
        return copy;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String trimSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return -1L;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String key(String agentId) {
        return (agentId == null || agentId.isBlank() ? "default" : agentId) + ":" + type();
    }

    private void mark(ChannelOutboundMessage message, String status, String detail) {
        Map<String, Object> meta = new LinkedHashMap<>(message.getMeta());
        meta.put("delivery_status", status);
        meta.put("delivery_detail", detail);
        message.setMeta(meta);
    }

    private class SlackSocketListener implements WebSocket.Listener {
        private final String agentId;
        private final Map<String, Object> config;
        private final ChannelInboundDispatcher dispatcher;
        private final StringBuilder buffer = new StringBuilder();

        SlackSocketListener(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
            this.agentId = agentId;
            this.config = config;
            this.dispatcher = dispatcher;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handle(webSocket, buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        private void handle(WebSocket webSocket, String raw) {
            Map<String, Object> frame = JsonUtils.fromJson(raw, Map.class);
            if (frame == null) return;
            String envelopeId = stringValue(frame.get("envelope_id"));
            if (!envelopeId.isBlank()) {
                webSocket.sendText(JsonUtils.toJson(Map.of("envelope_id", envelopeId)), true);
            }
            if (!"events_api".equals(stringValue(frame.get("type")))) return;
            Map<?, ?> payload = map(frame.get("payload"));
            Map<?, ?> event = map(payload.get("event"));
            if (!"message".equals(stringValue(event.get("type")))) return;
            if (!stringValue(event.get("bot_id")).isBlank() && !Boolean.TRUE.equals(config.get("accept_bot_messages"))) return;
            String subtype = stringValue(event.get("subtype"));
            if (!subtype.isBlank() && !"file_share".equals(subtype)) return;
            Map<String, Object> inboundPayload = new LinkedHashMap<>();
            inboundPayload.put("event", copyMap(event));
            inboundPayload.put("slack_payload", copyMap(payload));
            inboundPayload.put("bot_prefix", stringValue(config.get("bot_prefix")));
            inboundPayload.put("bot_user_id", slackBotUsers.getOrDefault(key(agentId), stringValue(config.get("bot_user_id"))));
            ChannelInboundMessage inbound = parseSlack(agentId, inboundPayload, Map.of("socket_mode", "true"));
            if (!inbound.getContent().isBlank() || !inbound.getAttachments().isEmpty()) {
                dispatcher.dispatch(inbound, 20);
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            sockets.remove(key(agentId));
            if (Boolean.TRUE.equals(config.get("enabled"))) {
                pollingExecutor.submit(() -> {
                    sleep(3000);
                    startSlackSocketMode(agentId, config, dispatcher);
                });
            }
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }
    }

    private class MattermostSocketListener implements WebSocket.Listener {
        private final String agentId;
        private final Map<String, Object> config;
        private final ChannelInboundDispatcher dispatcher;
        private final StringBuilder buffer = new StringBuilder();
        private int seq = 1;

        MattermostSocketListener(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
            this.agentId = agentId;
            this.config = config;
            this.dispatcher = dispatcher;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.sendText(JsonUtils.toJson(Map.of(
                    "seq", seq++,
                    "action", "authentication_challenge",
                    "data", Map.of("token", stringValue(config.get("bot_token"))))), true);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handle(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        private void handle(String raw) {
            Map<String, Object> frame = JsonUtils.fromJson(raw, Map.class);
            if (frame == null || !"posted".equals(stringValue(frame.get("event")))) return;
            Map<?, ?> data = map(frame.get("data"));
            Map<String, Object> post = JsonUtils.fromJson(stringValue(data.get("post")), Map.class);
            if (post == null) return;
            Map<?, ?> props = map(post.get("props"));
            if (Boolean.TRUE.equals(props.get("from_bot"))) return;
            post.put("team_id", stringValue(data.get("team_id")));
            ChannelInboundMessage inbound = parseMattermost(agentId, post, Map.of("websocket", "true"));
            if (!inbound.getContent().isBlank() || !inbound.getAttachments().isEmpty()) {
                dispatcher.dispatch(inbound, 20);
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            sockets.remove(key(agentId));
            if (Boolean.TRUE.equals(config.get("enabled"))) {
                pollingExecutor.submit(() -> {
                    sleep(3000);
                    startMattermostWebSocket(agentId, config, dispatcher);
                });
            }
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }
    }

    private class DiscordGatewayListener implements WebSocket.Listener {
        private final String agentId;
        private final Map<String, Object> config;
        private final ChannelInboundDispatcher dispatcher;
        private final StringBuilder buffer = new StringBuilder();
        private volatile long seq = -1L;

        DiscordGatewayListener(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
            this.agentId = agentId;
            this.config = config;
            this.dispatcher = dispatcher;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handle(webSocket, buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        private void handle(WebSocket webSocket, String raw) {
            Map<String, Object> frame = JsonUtils.fromJson(raw, Map.class);
            if (frame == null) return;
            if (frame.get("s") instanceof Number number) seq = number.longValue();
            int op = frame.get("op") instanceof Number number ? number.intValue() : -1;
            if (op == 10) {
                Map<?, ?> data = map(frame.get("d"));
                long interval = data.get("heartbeat_interval") instanceof Number n ? n.longValue() : 45_000L;
                webSocket.sendText(JsonUtils.toJson(Map.of(
                        "op", 2,
                        "d", Map.of(
                                "token", stringValue(config.get("bot_token")),
                                "intents", 37_377,
                                "properties", Map.of("os", "java", "browser", "melon", "device", "melon")))), true);
                Future<?> heartbeat = pollingExecutor.submit(() -> {
                    while (!Thread.currentThread().isInterrupted() && sockets.get(key(agentId)) == webSocket) {
                        sleep(interval);
                        Map<String, Object> heartbeatFrame = new LinkedHashMap<>();
                        heartbeatFrame.put("op", 1);
                        heartbeatFrame.put("d", seq >= 0 ? seq : null);
                        webSocket.sendText(JsonUtils.toJson(heartbeatFrame), true);
                    }
                });
                pollingTasks.put(key(agentId), heartbeat);
                return;
            }
            if (op == 11 || op != 0) return;
            if (!"MESSAGE_CREATE".equals(stringValue(frame.get("t")))) return;
            Map<?, ?> event = map(frame.get("d"));
            Map<?, ?> author = map(event.get("author"));
            if (Boolean.TRUE.equals(author.get("bot")) && !Boolean.TRUE.equals(config.get("accept_bot_messages"))) return;
            ChannelInboundMessage inbound = parseDiscord(agentId, copyMap(event), Map.of("gateway", "true"));
            if (!inbound.getContent().isBlank() || !inbound.getAttachments().isEmpty()) {
                dispatcher.dispatch(inbound, 20);
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            sockets.remove(key(agentId));
            Future<?> heartbeat = pollingTasks.remove(key(agentId));
            if (heartbeat != null) heartbeat.cancel(true);
            if (Boolean.TRUE.equals(config.get("enabled"))) {
                pollingExecutor.submit(() -> {
                    sleep(5000);
                    startDiscordGateway(agentId, config, dispatcher);
                });
            }
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }
    }
}
