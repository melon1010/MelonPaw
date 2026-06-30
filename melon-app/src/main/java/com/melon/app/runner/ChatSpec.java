/**
 * @author melon
 */
package com.melon.app.runner;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 聊天规格数据模型. 对应 Python app/runner/models.py 的 ChatSpec.
 * <p>
 * 描述一个聊天会话的元数据, 持久化到 ~/.melon/chats/ 目录.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatSpec {

    @JsonProperty("id")
    private String id;

    @JsonProperty("agent_id")
    private String agentId;

    @JsonProperty("title")
    private String title;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    @JsonProperty("last_message")
    private String lastMessage;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("model")
    private String model;

    public ChatSpec() {
    }

    public ChatSpec(String id, String agentId, String title) {
        this.id = id;
        this.agentId = agentId;
        this.title = title;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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

    public String getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    /**
     * 更新 updatedAt 为当前时间.
     */
    public void touch() {
        this.updatedAt = Instant.now().toString();
    }

    @Override
    public String toString() {
        return "ChatSpec{id='" + id + "', agentId='" + agentId + "', title='" + title + "'}";
    }
}
