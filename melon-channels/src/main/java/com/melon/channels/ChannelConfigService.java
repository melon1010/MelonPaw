package com.melon.channels;

import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChannelConfigService {

    private static final String MASK = "********";

    private final ConfigManager configManager;
    private final ChannelAdapterRegistry registry;
    private final ChannelAccessControlStore accessControlStore;

    public ChannelConfigService(ConfigManager configManager, ChannelAdapterRegistry registry) {
        this(configManager, registry, null);
    }

    public ChannelConfigService(ConfigManager configManager,
                                ChannelAdapterRegistry registry,
                                ChannelAccessControlStore accessControlStore) {
        this.configManager = configManager;
        this.registry = registry;
        this.accessControlStore = accessControlStore;
    }

    public List<String> types() {
        return ChannelTypes.BUILTIN;
    }

    public Map<String, Map<String, Object>> list(String agentId) {
        AgentConfig agent = agent(agentId);
        Map<String, Map<String, Object>> stored = agent != null && agent.getChannels() != null
                ? agent.getChannels()
                : Map.of();
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String type : ChannelTypes.BUILTIN) {
            result.put(type, publicConfig(type, stored.get(type)));
        }
        for (var entry : stored.entrySet()) {
            result.putIfAbsent(entry.getKey(), publicConfig(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    public Map<String, Object> get(String agentId, String channel) {
        return list(agentId).getOrDefault(channel, publicConfig(channel, Map.of()));
    }

    public Map<String, Map<String, Object>> updateAll(String agentId, Map<String, Object> body) {
        if (body == null) return list(agentId);
        for (var entry : body.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> raw) {
                update(agentId, entry.getKey(), copy(raw));
            }
        }
        return list(agentId);
    }

    public Map<String, Object> update(String agentId, String channel, Map<String, Object> body) {
        AgentConfig agent = agent(agentId);
        if (agent == null) return publicConfig(channel, body);
        Map<String, Map<String, Object>> channels = new LinkedHashMap<>(
                agent.getChannels() != null ? agent.getChannels() : Map.of());
        Map<String, Object> previous = new LinkedHashMap<>(channels.getOrDefault(channel, defaultConfig(channel)));
        Map<String, Object> merged = new LinkedHashMap<>(previous);
        if (body != null) {
            body.forEach((key, value) -> merged.put(key, preserveSecret(previous, key, value)));
        }
        migrateLegacyAccessFields(agentId, channel, merged, body != null ? body : Map.of());
        merged.put("type", channel);
        channels.put(channel, merged);
        agent.setChannels(channels);
        configManager.save();
        return publicConfig(channel, merged);
    }

    public Map<String, Object> runtimeConfig(String agentId, String channel) {
        AgentConfig agent = agent(agentId);
        if (agent == null || agent.getChannels() == null) return defaultConfig(channel);
        return new LinkedHashMap<>(agent.getChannels().getOrDefault(channel, defaultConfig(channel)));
    }

    public Map<String, Object> typeMeta(String type) {
        ChannelAdapter adapter = registry.get(type);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", type);
        meta.put("isBuiltin", ChannelTypes.BUILTIN.contains(type));
        meta.put("implemented", adapter.implemented());
        meta.put("supports_qrcode", adapter.supportsQrcode());
        meta.put("supports_streaming", adapter.supportsStreaming());
        meta.put("requires_webhook", adapter.requiresWebhook());
        return meta;
    }

    private Map<String, Object> publicConfig(String channel, Map<String, Object> raw) {
        Map<String, Object> config = new LinkedHashMap<>(defaultConfig(channel));
        if (raw != null) config.putAll(raw);
        config.put("type", channel);
        config.put("isBuiltin", ChannelTypes.BUILTIN.contains(channel));
        ChannelAdapter adapter = registry.get(channel);
        config.put("implemented", adapter.implemented());
        config.put("available", adapter.implemented());
        config.put("supports_qrcode", adapter.supportsQrcode());
        config.put("supports_streaming", adapter.supportsStreaming());
        config.put("requires_webhook", adapter.requiresWebhook());
        for (String key : ChannelTypes.SECRET_KEYS) {
            Object value = config.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                config.put(key, MASK);
            }
        }
        return config;
    }

    private Map<String, Object> defaultConfig(String channel) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", "console".equals(channel));
        config.put("bot_prefix", "");
        config.put("filter_tool_messages", false);
        config.put("filter_thinking", false);
        config.put("dm_policy", "open");
        config.put("group_policy", "open");
        config.put("allow_from", List.of());
        config.put("deny_message", "");
        config.put("require_mention", false);
        config.put("no_text_debounce", true);
        config.put("access_control_dm", false);
        config.put("access_control_group", false);
        config.put("dm_disabled", false);
        config.put("group_disabled", false);
        config.put("webhook_url", "");
        config.put("outgoing_webhook", "");
        channelDefaults(channel, config);
        return config;
    }

    private void channelDefaults(String channel, Map<String, Object> config) {
        switch (channel) {
            case "console" -> config.put("media_dir", "");
            case "imessage" -> {
                config.put("db_path", "~/Library/Messages/chat.db"); config.put("poll_sec", 1.0);
                config.put("media_dir", ""); config.put("max_decoded_size", 10 * 1024 * 1024);
            }
            case "discord" -> {
                config.put("bot_token", ""); config.put("http_proxy", ""); config.put("http_proxy_auth", "");
                config.put("accept_bot_messages", false); config.put("streaming_enabled", false); config.put("media_dir", "");
            }
            case "dingtalk" -> {
                config.put("client_id", ""); config.put("client_secret", ""); config.put("message_type", "markdown");
                config.put("cron_message_type", "markdown"); config.put("card_template_id", ""); config.put("card_template_key", "content");
                config.put("robot_code", ""); config.put("media_dir", ""); config.put("card_auto_layout", false);
                config.put("at_sender_on_reply", false); config.put("streaming_enabled", false); config.put("endpoint", "");
            }
            case "feishu" -> {
                config.put("app_id", ""); config.put("app_secret", ""); config.put("encrypt_key", "");
                config.put("verification_token", ""); config.put("media_dir", ""); config.put("domain", "feishu");
                config.put("streaming_enabled", false); config.put("share_session_in_group", false);
            }
            case "qq" -> {
                config.put("app_id", ""); config.put("client_secret", ""); config.put("ack_message", "");
                config.put("user_openid", ""); config.put("api_base", "https://api.sgroup.qq.com");
                config.put("markdown_enabled", true); config.put("media_dir", ""); config.put("max_reconnect_attempts", 100);
                config.put("portal_host", "q.qq.com");
            }
            case "telegram" -> {
                config.put("bot_token", ""); config.put("base_url", ""); config.put("http_proxy", ""); config.put("http_proxy_auth", "");
                config.put("show_typing", null); config.put("streaming_enabled", false);
            }
            case "slack" -> {
                config.put("bot_token", ""); config.put("app_token", ""); config.put("proxy", "");
                config.put("bot_user_id", ""); config.put("require_mention", true);
                config.put("streaming_enabled", false); config.put("media_dir", "");
            }
            case "mqtt" -> {
                config.put("host", ""); config.put("port", null); config.put("transport", "");
                config.put("clean_session", true); config.put("qos", 2); config.put("username", null); config.put("password", null);
                config.put("subscribe_topic", ""); config.put("publish_topic", "");
                config.put("tls_enabled", false); config.put("tls_ca_certs", null); config.put("tls_certfile", null); config.put("tls_keyfile", null);
            }
            case "matrix" -> {
                config.put("homeserver", ""); config.put("user_id", ""); config.put("access_token", "");
                config.put("auth_method", "token");
                config.put("group_allow_from", List.of()); config.put("groups", Map.of()); config.put("encryption", false);
                config.put("vision_enabled", true); config.put("history_limit", 50); config.put("password", "");
                config.put("device_name", "melonpaw-worker"); config.put("sync_timeout_ms", 30000);
                config.put("mention_pill_in_body", false); config.put("outbound_structured_mentions", true);
            }
            case "mattermost" -> {
                config.put("url", ""); config.put("bot_token", ""); config.put("media_dir", "");
                config.put("show_typing", null); config.put("thread_follow_without_mention", false);
            }
            case "wecom" -> {
                config.put("bot_id", ""); config.put("secret", ""); config.put("media_dir", "");
                config.put("welcome_text", ""); config.put("share_session_in_group", true);
                config.put("max_reconnect_attempts", -1); config.put("streaming_enabled", false);
                config.put("ws_url", ""); config.put("api_base", ""); config.put("reply_url", "");
            }
            case "wechat" -> {
                config.put("bot_token", ""); config.put("bot_token_file", ""); config.put("base_url", "");
                config.put("media_dir", ""); config.put("message_merge_enabled", false); config.put("message_merge_delay_ms", 0);
            }
            case "onebot" -> {
                config.put("ws_host", "0.0.0.0"); config.put("ws_port", 6199); config.put("access_token", "");
                config.put("share_session_in_group", false);
            }
            case "voice" -> {
                config.put("twilio_account_sid", ""); config.put("twilio_auth_token", ""); config.put("phone_number", "");
                config.put("phone_number_sid", ""); config.put("tts_provider", "google"); config.put("tts_voice", "en-US-Journey-D");
                config.put("stt_provider", "deepgram"); config.put("language", "en-US");
                config.put("welcome_greeting", "Hi! This is melonPaw. How can I help you?");
            }
            case "sip" -> {
                config.put("sip_mode", "livekit"); config.put("sip_host", "0.0.0.0"); config.put("sip_port", 5061);
                config.put("sip_username", ""); config.put("sip_password", ""); config.put("sip_server", "");
                config.put("sip_transport", "UDP"); config.put("rtp_port_low", 10000); config.put("rtp_port_high", 20000);
                config.put("dashscope_api_key", ""); config.put("tts_provider", "aliyun"); config.put("tts_voice", "");
                config.put("stt_provider", "aliyun"); config.put("language", "zh-CN"); config.put("welcome_greeting", "你好，我是melonPaw");
                config.put("call_timeout", 120.0); config.put("livekit_url", ""); config.put("livekit_api_key", "");
                config.put("livekit_api_secret", ""); config.put("livekit_sip_trunk_id", ""); config.put("livekit_room_name", "sip-inbound");
                config.put("livekit_output_sample_rate", 24000); config.put("max_concurrent_calls", 5);
            }
            case "xiaoyi" -> {
                config.put("ak", ""); config.put("sk", ""); config.put("agent_id", "");
                config.put("ws_url", "wss://hag.cloud.huawei.com/openclaw/v1/ws/link");
                config.put("backup_ws_url", "wss://116.63.174.231/openclaw/v1/ws/link");
                config.put("task_timeout_ms", 3600000); config.put("media_dir", "");
            }
            case "yuanbao" -> {
                config.put("app_id", ""); config.put("app_secret", ""); config.put("api_domain", "bot.yuanbao.tencent.com");
                config.put("media_dir", ""); config.put("accept_bot_messages", false);
                config.put("send_url", ""); config.put("websocket_url", "wss://bot-wss.yuanbao.tencent.com/wss/connection");
            }
            default -> { }
        }
    }

    private Object preserveSecret(Map<String, Object> previous, String key, Object value) {
        if (ChannelTypes.SECRET_KEYS.contains(key) && MASK.equals(value)) {
            return previous.getOrDefault(key, "");
        }
        return value;
    }

    private void migrateLegacyAccessFields(String agentId, String channel,
                                           Map<String, Object> merged,
                                           Map<String, Object> incoming) {
        if (incoming.containsKey("dm_policy")) {
            String policy = String.valueOf(incoming.get("dm_policy"));
            if ("allowlist".equals(policy)) {
                merged.put("access_control_dm", true);
            } else if ("disabled".equals(policy)) {
                merged.put("dm_disabled", true);
            }
            merged.remove("dm_policy");
        }
        if (incoming.containsKey("group_policy")) {
            String policy = String.valueOf(incoming.get("group_policy"));
            if ("allowlist".equals(policy)) {
                merged.put("access_control_group", true);
            } else if ("disabled".equals(policy)) {
                merged.put("group_disabled", true);
            }
            merged.remove("group_policy");
        }
        Object allowFrom = incoming.get("allow_from");
        if (accessControlStore != null && allowFrom instanceof List<?> list && !list.isEmpty()) {
            List<Map<String, Object>> entries = list.stream()
                    .map(String::valueOf)
                    .filter(value -> !value.isBlank())
                    .map(userId -> Map.<String, Object>of("channel", channel, "user_id", userId))
                    .toList();
            if (!entries.isEmpty()) {
                accessControlStore.addUsers(agentId, "whitelist", entries);
            }
            merged.remove("allow_from");
        }
    }

    private AgentConfig agent(String agentId) {
        String id = agentId == null || agentId.isBlank() ? "default" : agentId;
        return configManager.getConfig().getAgents().get(id);
    }

    private Map<String, Object> copy(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
