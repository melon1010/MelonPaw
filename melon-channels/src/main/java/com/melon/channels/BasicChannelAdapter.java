package com.melon.channels;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static com.melon.core.util.ValueUtils.stringValue;

public class BasicChannelAdapter implements ChannelAdapter {

    private final String type;
    private final boolean implemented;
    private final boolean qrcode;
    private final boolean streaming;
    private final boolean webhook;
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    public BasicChannelAdapter(String type, boolean implemented, boolean qrcode, boolean streaming, boolean webhook) {
        this.type = type;
        this.implemented = implemented;
        this.qrcode = qrcode;
        this.streaming = streaming;
        this.webhook = webhook;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public boolean implemented() {
        return implemented;
    }

    @Override
    public boolean supportsQrcode() {
        return qrcode;
    }

    @Override
    public boolean supportsStreaming() {
        return streaming;
    }

    @Override
    public boolean requiresWebhook() {
        return webhook;
    }

    @Override
    public CompletableFuture<ChannelHealth> start(String agentId, Map<String, Object> config) {
        if (!implemented) {
            return CompletableFuture.completedFuture(health(agentId, config));
        }
        running.put(key(agentId), true);
        return CompletableFuture.completedFuture(health(agentId, config));
    }

    @Override
    public CompletableFuture<ChannelHealth> stop(String agentId) {
        running.put(key(agentId), false);
        return CompletableFuture.completedFuture(ChannelHealth.of(type, "stopped", false, true, implemented, "Channel stopped"));
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        boolean enabled = Boolean.TRUE.equals(config != null ? config.get("enabled") : null);
        boolean isRunning = Boolean.TRUE.equals(running.get(key(agentId)));
        if (!implemented) {
            return ChannelHealth.of(type, "unsupported", false, false, false,
                    "Java adapter is registered but real platform integration is not implemented yet.");
        }
        return ChannelHealth.of(type, isRunning ? "running" : (enabled ? "configured" : "stopped"),
                isRunning, true, true, enabled ? "Channel is configured." : "Channel is disabled.");
    }

    @Override
    public ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body, Map<String, String> headers) {
        Map<String, Object> payload = body != null ? body : Map.of();
        ChannelInboundMessage message = new ChannelInboundMessage();
        message.setAgentId(agentId);
        message.setChannel(type);
        message.setUserId(first(payload, "user_id", "sender_id", "userId", "from"));
        message.setSessionId(first(payload, "session_id", "sessionId", "conversation_id", "chat_id"));
        message.setContent(first(payload, "content", "text", "message"));
        Object attachments = payload.get("attachments");
        if (attachments instanceof java.util.List<?> items) {
            message.setAttachments(items.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(item -> {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        item.forEach((key, value) -> copy.put(String.valueOf(key), value));
                        return copy;
                    })
                    .toList());
        }
        message.setChannelMeta(new LinkedHashMap<>(payload));
        message.setReplyTo(new ChannelAddress("dm", message.getUserId(), Map.of("headers", headers != null ? headers : Map.of())));
        return message;
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        return CompletableFuture.completedFuture(message);
    }

    private String first(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            String value = stringValue(payload.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String key(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }
}
