package com.melon.channels;

import java.util.LinkedHashMap;
import java.util.Map;

public class ChannelOutboundMessage {

    private String agentId = "default";
    private String channel = "console";
    private String userId = "default";
    private String sessionId = "default";
    private String text = "";
    private String format = "markdown";
    private ChannelAddress to = new ChannelAddress("dm", "default", Map.of());
    private Map<String, Object> meta = new LinkedHashMap<>();

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = value(agentId, "default"); }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = value(channel, "console"); }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = value(userId, "default"); }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = value(sessionId, "default"); }

    public String getText() { return text; }
    public void setText(String text) { this.text = text != null ? text : ""; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = value(format, "markdown"); }

    public ChannelAddress getTo() { return to; }
    public void setTo(ChannelAddress to) { this.to = to != null ? to : new ChannelAddress("dm", userId, Map.of()); }

    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> meta) { this.meta = meta != null ? new LinkedHashMap<>(meta) : new LinkedHashMap<>(); }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
