package com.melon.channels;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.melon.core.util.ValueUtils.booleanValue;
import static com.melon.core.util.ValueUtils.stringValue;

public class ChannelManager {

    private static final Logger log = LoggerFactory.getLogger(ChannelManager.class);

    private final ChannelAdapterRegistry registry;
    private final ChannelConfigService configService;
    private final ChannelAccessControlStore accessControlStore;
    private final ChannelQueueManager queueManager;
    private final ChannelProcessor processor;

    public ChannelManager(ChannelAdapterRegistry registry,
                          ChannelConfigService configService,
                          ChannelAccessControlStore accessControlStore,
                          ChannelQueueManager queueManager,
                          ChannelProcessor processor) {
        this.registry = registry;
        this.configService = configService;
        this.accessControlStore = accessControlStore;
        this.queueManager = queueManager;
        this.processor = processor;
    }

    public CompletableFuture<ChannelHealth> start(String agentId, String channel) {
        ChannelAdapter adapter = registry.get(channel);
        return adapter.start(agentId, configService.runtimeConfig(agentId, channel), this::enqueue);
    }

    public CompletableFuture<ChannelHealth> stop(String agentId, String channel) {
        return registry.get(channel).stop(agentId);
    }

    public CompletableFuture<ChannelHealth> restart(String agentId, String channel) {
        return stop(agentId, channel).thenCompose(ignored -> start(agentId, channel));
    }

    public ChannelHealth health(String agentId, String channel) {
        return registry.get(channel).health(agentId, configService.runtimeConfig(agentId, channel));
    }

    public Map<String, Object> healthAll(String agentId) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String type : configService.types()) {
            result.put(type, health(agentId, type).toMap());
        }
        return result;
    }

    public CompletableFuture<ChannelOutboundMessage> handleWebhook(String agentId, String channel,
                                                                   Map<String, Object> body,
                                                                   Map<String, String> headers) {
        ChannelAdapter adapter = registry.get(channel);
        ChannelInboundMessage inbound = adapter.parseWebhook(agentId, body, headers);
        inbound.setAgentId(agentId);
        inbound.setChannel(channel);
        return enqueue(inbound, 20);
    }

    public CompletableFuture<ChannelOutboundMessage> enqueue(ChannelInboundMessage message, int priority) {
        Map<String, Object> config = configService.runtimeConfig(message.getAgentId(), message.getChannel());
        normalizeSession(message, config);
        ChannelOutboundMessage skipped = applyInboundPolicy(message, config);
        if (skipped != null) {
            Map<String, Object> meta = skipped.getMeta() != null ? skipped.getMeta() : Map.of();
            log.info("Channel inbound ignored: agent={}, channel={}, user={}, session={}, reason={}",
                    message.getAgentId(), message.getChannel(), message.getUserId(), message.getSessionId(), meta.get("reason"));
            return CompletableFuture.completedFuture(skipped);
        }
        if (!accessControlStore.allowed(message.getAgentId(), message.getChannel(), message.getUserId(),
                message.getChannelMeta(), config)) {
            accessControlStore.addPending(message.getAgentId(), message);
            return registry.get(message.getChannel()).send(denied(message, config, "access_control"), config);
        }
        return queueManager.enqueue(message, priority, processor)
                .thenCompose(out -> registry.get(message.getChannel()).send(out, config));
    }

    private ChannelOutboundMessage applyInboundPolicy(ChannelInboundMessage message, Map<String, Object> config) {
        if (!booleanValue(config.get("enabled"), false)) {
            return ignored(message, "channel_disabled");
        }
        if (requiresAddressing(message, config) && !addressed(message, config)) {
            return ignored(message, "not_addressed");
        }
        return null;
    }

    private boolean requiresAddressing(ChannelInboundMessage message, Map<String, Object> config) {
        return booleanValue(config.get("require_mention"), false) && isGroup(message.getChannelMeta());
    }

    private boolean addressed(ChannelInboundMessage message, Map<String, Object> config) {
        Map<String, Object> meta = message.getChannelMeta() != null ? message.getChannelMeta() : Map.of();
        for (String key : List.of("bot_mentioned", "has_bot_command", "mentioned", "is_mentioned", "mentions_bot", "at_bot", "to_me")) {
            if (booleanValue(meta.get(key), false)) return true;
        }
        String text = message.getContent();
        for (String key : List.of("bot_name", "bot_id", "robot_code", "app_id")) {
            String value = stringValue(config.get(key));
            if (!value.isBlank() && text.contains("@" + value)) return true;
        }
        for (String key : List.of("mentions", "at_users", "mentioned_users")) {
            Object raw = meta.get(key);
            if (raw instanceof List<?> list && !list.isEmpty()) return true;
        }
        return false;
    }

    private boolean isGroup(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) return false;
        if (booleanValue(meta.get("is_group"), false)) return true;
        for (String key : List.of("group_id", "chatid", "wecom_chatid", "group_openid", "guild_id")) {
            if (!stringValue(meta.get(key)).isBlank()) return true;
        }
        String chatType = stringValue(meta.get("chat_type"),
                stringValue(meta.get("wecom_chat_type"),
                        stringValue(meta.get("feishu_chat_type"),
                                stringValue(meta.get("channel_type")))));
        if ("group".equalsIgnoreCase(chatType) || "channel".equalsIgnoreCase(chatType)) return true;
        String slackChannel = stringValue(meta.get("channel"), stringValue(meta.get("slack_channel_id")));
        return !slackChannel.isBlank() && !slackChannel.startsWith("D");
    }

    private ChannelOutboundMessage denied(ChannelInboundMessage message, Map<String, Object> config, String reason) {
        ChannelOutboundMessage out = baseOutbound(message);
        String denyMessage = stringValue(config.get("deny_message"), "Access pending approval.");
        out.setText(denyMessage.isBlank() ? "Access pending approval." : denyMessage);
        out.setMeta(Map.of("blocked", true, "reason", reason));
        return out;
    }

    private ChannelOutboundMessage ignored(ChannelInboundMessage message, String reason) {
        ChannelOutboundMessage out = baseOutbound(message);
        out.setText("");
        out.setMeta(Map.of("ignored", true, "reason", reason));
        return out;
    }

    private ChannelOutboundMessage baseOutbound(ChannelInboundMessage message) {
        ChannelOutboundMessage out = new ChannelOutboundMessage();
        out.setAgentId(message.getAgentId());
        out.setChannel(message.getChannel());
        out.setUserId(message.getUserId());
        out.setSessionId(message.getSessionId());
        out.setTo(message.getReplyTo());
        return out;
    }

    private void normalizeSession(ChannelInboundMessage message, Map<String, Object> config) {
        Map<String, Object> meta = message.getChannelMeta() != null ? message.getChannelMeta() : Map.of();
        String channel = message.getChannel();
        if ("onebot".equals(channel)) {
            String groupId = stringValue(meta.get("group_id"));
            if (!groupId.isBlank()) {
                message.setSessionId(booleanValue(config.get("share_session_in_group"), false)
                        ? "onebot:g:" + groupId
                        : "onebot:" + groupId + ":" + message.getUserId());
            }
            return;
        }
        if ("feishu".equals(channel)) {
            String chatType = stringValue(meta.get("feishu_chat_type"));
            String chatId = stringValue(meta.get("feishu_chat_id"));
            String sender = stringValue(meta.get("feishu_sender_id"), message.getUserId());
            if ("group".equals(chatType) && !chatId.isBlank()) {
                String appId = stringValue(config.get("app_id"));
                String appSuffix = appId.length() >= 4 ? appId.substring(appId.length() - 4) : appId;
                message.setSessionId(appSuffix.isBlank() ? shortId(chatId) : appSuffix + "_" + shortId(chatId));
            } else if (!sender.isBlank()) {
                message.setSessionId(shortId(sender));
            }
            return;
        }
        if ("wecom".equals(channel)) {
            String chatType = stringValue(meta.get("wecom_chat_type"));
            String chatId = stringValue(meta.get("wecom_chatid"));
            if ("group".equals(chatType) && !chatId.isBlank() && !booleanValue(config.get("share_session_in_group"), true)) {
                message.setSessionId("wecom:group:" + chatId + ":" + message.getUserId());
            }
            return;
        }
        if ("dingtalk".equals(channel)) {
            String conversation = stringValue(meta.get("conversation_id"), stringValue(meta.get("conversationId")));
            if (!conversation.isBlank()) {
                message.setSessionId(shortId(conversation));
            }
        }
    }

    private String shortId(String value) {
        String text = stringValue(value);
        return text.length() >= 8 ? text.substring(text.length() - 8) : text;
    }
}
