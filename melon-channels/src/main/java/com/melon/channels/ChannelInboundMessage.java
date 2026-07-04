package com.melon.channels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChannelInboundMessage {

    private String agentId = "default";
    private String channel = "console";
    private String userId = "default";
    private String sessionId = "default";
    private String content = "";
    private List<Map<String, Object>> attachments = new ArrayList<>();
    private Map<String, Object> channelMeta = new LinkedHashMap<>();
    private ChannelAddress replyTo = new ChannelAddress("dm", "default", Map.of());

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = value(agentId, "default"); }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = value(channel, "console"); }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = value(userId, "default"); }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = value(sessionId, "default"); }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content != null ? content : ""; }

    public List<Map<String, Object>> getAttachments() { return attachments; }
    public void setAttachments(List<Map<String, Object>> attachments) {
        this.attachments = attachments != null ? new ArrayList<>(attachments) : new ArrayList<>();
    }

    public Map<String, Object> getChannelMeta() { return channelMeta; }
    public void setChannelMeta(Map<String, Object> channelMeta) {
        this.channelMeta = channelMeta != null ? new LinkedHashMap<>(channelMeta) : new LinkedHashMap<>();
    }

    public ChannelAddress getReplyTo() { return replyTo; }
    public void setReplyTo(ChannelAddress replyTo) { this.replyTo = replyTo != null ? replyTo : new ChannelAddress("dm", userId, Map.of()); }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
