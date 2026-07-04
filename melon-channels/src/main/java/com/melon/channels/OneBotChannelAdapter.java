package com.melon.channels;

import com.melon.core.util.JsonUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static com.melon.core.util.ValueUtils.stringValue;

public class OneBotChannelAdapter extends BasicChannelAdapter {

    private final Map<String, OneBotConnection> connections = new ConcurrentHashMap<>();

    public OneBotChannelAdapter() {
        super("onebot", true, false, false, true);
    }

    public void attach(String agentId, OneBotConnection connection) {
        connections.put(key(agentId), connection);
    }

    public void detach(String agentId, String connectionId) {
        connections.computeIfPresent(key(agentId), (ignored, existing) ->
                existing.id().equals(connectionId) ? null : existing);
    }

    @Override
    public CompletableFuture<ChannelHealth> start(String agentId, Map<String, Object> config) {
        return CompletableFuture.completedFuture(health(agentId, config));
    }

    @Override
    public CompletableFuture<ChannelHealth> stop(String agentId) {
        connections.remove(key(agentId));
        return CompletableFuture.completedFuture(health(agentId, Map.of("enabled", false)));
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        boolean enabled = Boolean.TRUE.equals(config != null ? config.get("enabled") : null);
        boolean connected = connections.containsKey(key(agentId));
        if (!enabled) {
            return ChannelHealth.of(type(), "stopped", connected, true, true, "Channel is disabled.");
        }
        return ChannelHealth.of(type(), connected ? "running" : "waiting_connection",
                connected, true, true,
                connected ? "OneBot reverse WebSocket is connected." : "Waiting for OneBot reverse WebSocket connection.");
    }

    @Override
    public ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body, Map<String, String> headers) {
        Map<String, Object> payload = body != null ? body : Map.of();
        String groupId = stringValue(payload.get("group_id"));
        String userId = stringValue(payload.get("user_id"));
        String session = !groupId.isBlank() ? "group:" + groupId : "private:" + userId;
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(userId);
        inbound.setSessionId(session);
        inbound.setContent(first(payload, "raw_message", "message", "text"));
        inbound.setChannelMeta(new LinkedHashMap<>(payload));
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("group_id", groupId);
        reply.put("user_id", userId);
        inbound.setReplyTo(new ChannelAddress(groupId.isBlank() ? "private" : "group",
                groupId.isBlank() ? userId : groupId, reply));
        return inbound;
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        OneBotConnection connection = connections.get(key(message.getAgentId()));
        if (connection == null) {
            mark(message, "failed", "OneBot WebSocket is not connected.");
            return CompletableFuture.completedFuture(message);
        }
        String groupId = extra(message, "group_id");
        String userId = extra(message, "user_id");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message.getText());
        String action;
        if (!groupId.isBlank()) {
            action = "send_group_msg";
            params.put("group_id", groupId);
        } else {
            action = "send_private_msg";
            params.put("user_id", !userId.isBlank() ? userId : message.getUserId());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("params", params);
        payload.put("echo", "melon-" + UUID.randomUUID().toString().replace("-", ""));
        return connection.send(JsonUtils.toJson(payload))
                .thenApply(ignored -> {
                    mark(message, "sent", action);
                    return message;
                })
                .exceptionally(error -> {
                    mark(message, "failed", error.getMessage());
                    return message;
                });
    }

    @Override
    public void close() {
        connections.clear();
    }

    private String extra(ChannelOutboundMessage message, String key) {
        if (message.getTo() == null || message.getTo().getExtra() == null) return "";
        return stringValue(message.getTo().getExtra().get(key));
    }

    private String first(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            String value = stringValue(map.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private void mark(ChannelOutboundMessage message, String status, String detail) {
        Map<String, Object> meta = new LinkedHashMap<>(message.getMeta());
        meta.put("delivery_status", status);
        meta.put("delivery_detail", detail != null ? detail : "");
        message.setMeta(meta);
    }

    private String key(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }

    @FunctionalInterface
    public interface OneBotConnection {
        CompletableFuture<Void> send(String payload);

        default String id() {
            return "";
        }
    }
}
