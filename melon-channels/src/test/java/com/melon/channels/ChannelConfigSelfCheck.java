package com.melon.channels;

import com.melon.core.config.ConfigManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class ChannelConfigSelfCheck {

    private ChannelConfigSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-channel-config-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        ChannelAccessControlStore accessControlStore = new ChannelAccessControlStore(configManager);
        ChannelConfigService service = new ChannelConfigService(configManager, new ChannelAdapterRegistry(), accessControlStore);

        List<String> types = service.types();
        for (String type : List.of("console", "dingtalk", "feishu", "imessage", "discord", "telegram",
                "qq", "wechat", "wecom", "yuanbao", "matrix", "sip", "xiaoyi", "slack", "mqtt",
                "mattermost", "onebot", "voice")) {
            if (!types.contains(type)) {
                throw new AssertionError("missing channel type: " + type);
            }
        }

        Map<String, Map<String, Object>> listed = service.list("default");
        if (!listed.keySet().containsAll(types) || !Boolean.TRUE.equals(listed.get("console").get("enabled"))) {
            throw new AssertionError("default channel list mismatch: " + listed);
        }
        if (!listed.get("feishu").containsKey("verification_token")
                || !listed.get("mqtt").containsKey("tls_enabled")
                || !listed.get("sip").containsKey("livekit_sip_trunk_id")) {
            throw new AssertionError("frontend channel defaults are incomplete: " + listed);
        }
        if (!Boolean.TRUE.equals(listed.get("feishu").get("supports_qrcode"))
                || !Boolean.TRUE.equals(listed.get("dingtalk").get("supports_qrcode"))
                || Boolean.TRUE.equals(listed.get("telegram").get("requires_webhook"))
                || Boolean.TRUE.equals(listed.get("slack").get("requires_webhook"))
                || Boolean.TRUE.equals(listed.get("matrix").get("requires_webhook"))
                || Boolean.TRUE.equals(listed.get("mattermost").get("requires_webhook"))
                || Boolean.TRUE.equals(listed.get("discord").get("requires_webhook"))) {
            throw new AssertionError("frontend channel capability metadata drifted: " + listed);
        }
        if (!"~/Library/Messages/chat.db".equals(listed.get("imessage").get("db_path"))
                || !Integer.valueOf(10 * 1024 * 1024).equals(listed.get("imessage").get("max_decoded_size"))) {
            throw new AssertionError("imessage defaults are not aligned with python: " + listed.get("imessage"));
        }
        if (!Integer.valueOf(2).equals(listed.get("mqtt").get("qos"))
                || !Integer.valueOf(30000).equals(listed.get("matrix").get("sync_timeout_ms"))
                || !Integer.valueOf(24000).equals(listed.get("sip").get("livekit_output_sample_rate"))
                || !listed.get("telegram").containsKey("base_url")
                || Boolean.TRUE.equals(listed.get("onebot").get("share_session_in_group"))) {
            throw new AssertionError("python channel field defaults drifted: " + listed);
        }

        Map<String, Object> saved = service.update("default", "slack", Map.of(
                "enabled", true,
                "bot_token", "xoxb-real",
                "app_token", "xapp-real"
        ));
        if (!"********".equals(saved.get("bot_token")) || !"********".equals(saved.get("app_token"))) {
            throw new AssertionError("secret fields were not masked: " + saved);
        }

        service.update("default", "slack", Map.of("bot_token", "********", "app_token", "********", "proxy", "http://proxy"));
        Map<String, Object> runtime = service.runtimeConfig("default", "slack");
        if (!"xoxb-real".equals(runtime.get("bot_token"))
                || !"xapp-real".equals(runtime.get("app_token"))
                || !"http://proxy".equals(runtime.get("proxy"))) {
            throw new AssertionError("masked secret update should preserve stored secret: " + runtime);
        }

        Map<String, Object> custom = service.update("default", "custom-webhook", Map.of(
                "enabled", true,
                "token", "custom-secret",
                "extra", "kept"
        ));
        if (!"kept".equals(custom.get("extra")) || !"********".equals(custom.get("token"))) {
            throw new AssertionError("custom channel config should preserve unknown fields and mask secrets: " + custom);
        }

        ChannelAdapterRegistry registry = new ChannelAdapterRegistry();
        AtomicReference<ChannelInboundMessage> captured = new AtomicReference<>();
        ChannelProcessor processor = inbound -> {
            captured.set(inbound);
            ChannelOutboundMessage outbound = new ChannelOutboundMessage();
            outbound.setAgentId(inbound.getAgentId());
            outbound.setChannel(inbound.getChannel());
            outbound.setUserId(inbound.getUserId());
            outbound.setSessionId(inbound.getSessionId());
            outbound.setTo(inbound.getReplyTo());
            outbound.setText("ok");
            return CompletableFuture.completedFuture(outbound);
        };
        ChannelManager manager = new ChannelManager(registry, service, accessControlStore, new ChannelQueueManager(), processor);
        service.update("default", "console", Map.of("enabled", false));
        ChannelOutboundMessage disabled = manager.enqueue(message("default", "console", "u1", "s1", "hello", Map.of()), 20).get();
        if (!Boolean.TRUE.equals(disabled.getMeta().get("ignored"))
                || !"channel_disabled".equals(disabled.getMeta().get("reason"))) {
            throw new AssertionError("disabled channel should ignore inbound messages: " + disabled.getMeta());
        }
        service.update("default", "console", Map.of("enabled", true, "allow_from", List.of("u2")));
        Map<String, Object> migratedAllowFrom = accessControlStore.channel("default", "console");
        if (!String.valueOf(migratedAllowFrom.get("whitelist")).contains("u2")
                || !service.runtimeConfig("default", "console").getOrDefault("allow_from", List.of()).equals(List.of())) {
            throw new AssertionError("allow_from should migrate into access_control whitelist: " + migratedAllowFrom);
        }
        captured.set(null);
        manager.enqueue(message("default", "console", "u1", "s1", "hello", Map.of()), 20).get();
        if (captured.get() == null) {
            throw new AssertionError("legacy allow_from alone should not block runtime without access_control enabled");
        }
        service.update("default", "console", Map.of("allow_from", List.of(), "bot_prefix", "/bot"));
        captured.set(null);
        manager.enqueue(message("default", "console", "u1", "s1", "/bot hello", Map.of()), 20).get();
        if (captured.get() == null || !"/bot hello".equals(captured.get().getContent())) {
            throw new AssertionError("bot_prefix should not strip inbound messages: "
                    + (captured.get() != null ? captured.get().getContent() : null));
        }
        captured.set(null);
        ChannelOutboundMessage noPrefix = manager.enqueue(message("default", "console", "u1", "s1", "hello", Map.of()), 20).get();
        if (captured.get() == null || Boolean.TRUE.equals(noPrefix.getMeta().get("ignored"))) {
            throw new AssertionError("bot_prefix should not require addressed inbound messages: " + noPrefix.getMeta());
        }
        String rendered = new ChannelMessageRenderer().render(List.of(Map.of(
                "type", "message",
                "content", List.of(Map.of("text", "answer"))
        )), service.runtimeConfig("default", "console"));
        if (!"/bot  answer".equals(rendered)) {
            throw new AssertionError("bot_prefix should be applied to outbound rendered text: " + rendered);
        }
        service.update("default", "console", Map.of("bot_prefix", "", "require_mention", true));
        captured.set(null);
        manager.enqueue(message("default", "console", "u1", "s1", "hello",
                Map.of("is_group", true, "mentioned", true)), 20).get();
        if (captured.get() == null) {
            throw new AssertionError("require_mention should accept explicitly mentioned messages");
        }
        captured.set(null);
        ChannelOutboundMessage notMentioned = manager.enqueue(message("default", "console", "u1", "s1", "hello",
                Map.of("is_group", true)), 20).get();
        if (captured.get() != null || !"not_addressed".equals(notMentioned.getMeta().get("reason"))) {
            throw new AssertionError("require_mention should ignore unmentioned messages: " + notMentioned.getMeta());
        }
        captured.set(null);
        manager.enqueue(message("default", "console", "u1", "s1", "hello", Map.of()), 20).get();
        if (captured.get() == null) {
            throw new AssertionError("require_mention should not block direct messages");
        }
        service.update("default", "console", Map.of("dm_policy", "allowlist"));
        if (!Boolean.TRUE.equals(service.runtimeConfig("default", "console").get("access_control_dm"))
                || service.runtimeConfig("default", "console").containsKey("dm_policy")) {
            throw new AssertionError("dm_policy should migrate to access_control_dm and be removed from stored config: "
                    + service.runtimeConfig("default", "console"));
        }
        service.update("default", "console", Map.of(
                "require_mention", false,
                "allow_from", List.of(),
                "access_control_dm", true,
                "deny_message", "denied"
        ));
        captured.set(null);
        ChannelOutboundMessage denied = manager.enqueue(message("default", "console", "u3", "s1", "hello", Map.of()), 20).get();
        if (captured.get() != null || !"denied".equals(denied.getText())
                || !"access_control".equals(denied.getMeta().get("reason"))) {
            throw new AssertionError("access control should deny and return a channel reply: " + denied.getMeta());
        }
        service.update("default", "onebot", Map.of("enabled", true));
        ChannelInboundMessage onebotGroup = new ChannelInboundMessage();
        onebotGroup.setAgentId("default");
        onebotGroup.setChannel("onebot");
        onebotGroup.setUserId("u1");
        onebotGroup.setSessionId("group:g1");
        onebotGroup.setChannelMeta(Map.of("group_id", "g1"));
        manager.enqueue(onebotGroup, 20).get();
        if (!"onebot:g1:u1".equals(captured.get().getSessionId())) {
            throw new AssertionError("onebot group session should isolate users by default: " + captured.get().getSessionId());
        }
        service.update("default", "onebot", Map.of("share_session_in_group", true));
        captured.set(null);
        manager.enqueue(onebotGroup, 20).get();
        if (!"onebot:g:g1".equals(captured.get().getSessionId())) {
            throw new AssertionError("onebot shared group session was not applied: " + captured.get().getSessionId());
        }
        registry.close();
    }

    private static ChannelInboundMessage message(String agentId, String channel, String userId, String sessionId,
                                                 String content, Map<String, Object> meta) {
        ChannelInboundMessage message = new ChannelInboundMessage();
        message.setAgentId(agentId);
        message.setChannel(channel);
        message.setUserId(userId);
        message.setSessionId(sessionId);
        message.setContent(content);
        message.setChannelMeta(meta);
        message.setReplyTo(new ChannelAddress("dm", userId, Map.of()));
        return message;
    }
}
