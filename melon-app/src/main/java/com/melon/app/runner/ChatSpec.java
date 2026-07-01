/**
 * @author melon
 */
package com.melon.app.runner;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天规格数据模型. 对应 Python app/runner/models.py 的 ChatSpec.
 * <p>
 * 描述一个聊天会话的元数据, 持久化到 workspace/chats.json.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatSpec {

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("user_id")
    private String userId = "default";

    @JsonProperty("channel")
    private String channel = "console";

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    @JsonProperty("meta")
    private Map<String, Object> meta = new LinkedHashMap<>();

    @JsonProperty("status")
    private String status = "idle";

    @JsonProperty("pinned")
    private boolean pinned;

    @JsonProperty("source")
    private String source = "chat";

    @JsonProperty("agent_id")
    private String agentId;

    public ChatSpec() {
    }

    public ChatSpec(String id, String agentId, String name) {
        this.id = id;
        this.agentId = agentId;
        this.name = name;
        String now = Instant.now().toString();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Map<String, Object> getMeta() {
        if (meta == null) meta = new LinkedHashMap<>();
        return meta;
    }

    public void setMeta(Map<String, Object> meta) {
        this.meta = meta != null ? meta : new LinkedHashMap<>();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    /**
     * 更新 updatedAt 为当前时间.
     */
    public void touch() {
        this.updatedAt = Instant.now().toString();
    }

    @Override
    public String toString() {
        return "ChatSpec{id='" + id + "', agentId='" + agentId + "', name='" + name + "'}";
    }
}
