/**
 * @author melon
 */
package com.melon.app.runner;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import com.melon.core.util.SafePathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天管理器. 对应 Python app/runner/manager.py.
 * <p>
 * 管理 ChatSpec 的 CRUD 操作, 存储到 ~/.melon/chats/ 目录的 JSON 文件.
 */
@Component
public class ChatManager {

    private static final Logger log = LoggerFactory.getLogger(ChatManager.class);

    private final Path chatsDir;

    public ChatManager(ConfigManager configManager) {
        this.chatsDir = configManager.resolveHomeDir().resolve("chats");
    }

    // ======================== CRUD ========================

    /**
     * 创建新的聊天会话.
     *
     * @param agentId 关联的 Agent ID
     * @param title   聊天标题
     * @param model   使用的模型
     * @return 创建的 ChatSpec
     */
    public ChatSpec create(String agentId, String title, String model) {
        String chatId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        ChatSpec spec = new ChatSpec(chatId, agentId, title != null ? title : "New Chat");
        spec.setSessionId(UUID.randomUUID().toString());
        spec.setModel(model);
        spec.setCreatedAt(now);
        spec.setUpdatedAt(now);
        save(spec);
        log.info("Chat created: id={}, agent={}, title={}", chatId, agentId, title);
        return spec;
    }

    /**
     * 列出所有聊天会话, 按 updatedAt 降序排列.
     */
    public List<ChatSpec> list() {
        List<ChatSpec> chats = new ArrayList<>();
        ensureDir();
        java.io.File dir = chatsDir.toFile();
        java.io.File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) {
            return chats;
        }
        for (java.io.File file : files) {
            ChatSpec spec = JsonUtils.load(file.toPath(), ChatSpec.class);
            if (spec != null && spec.getId() != null) {
                chats.add(spec);
            }
        }
        chats.sort(Comparator.comparing(
                ChatSpec::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return chats;
    }

    /**
     * 获取指定 ID 的聊天会话.
     */
    public ChatSpec get(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return null;
        }
        Path file = chatFilePath(chatId);
        return JsonUtils.load(file, ChatSpec.class);
    }

    /**
     * 更新聊天会话. 自动刷新 updatedAt.
     */
    public ChatSpec update(ChatSpec spec) {
        if (spec == null || spec.getId() == null) {
            throw new IllegalArgumentException("ChatSpec or id is null");
        }
        spec.setUpdatedAt(Instant.now().toString());
        save(spec);
        log.info("Chat updated: id={}", spec.getId());
        return spec;
    }

    /**
     * 删除指定 ID 的聊天会话.
     */
    public boolean delete(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return false;
        }
        Path file = chatFilePath(chatId);
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                log.info("Chat deleted: id={}", chatId);
            }
            return deleted;
        } catch (java.io.IOException e) {
            log.error("Failed to delete chat: id={}", chatId, e);
            return false;
        }
    }

    /**
     * 更新聊天的最后一条消息.
     */
    public ChatSpec updateLastMessage(String chatId, String lastMessage) {
        ChatSpec spec = get(chatId);
        if (spec == null) {
            log.warn("Chat not found for last message update: id={}", chatId);
            return null;
        }
        spec.setLastMessage(lastMessage);
        spec.setUpdatedAt(Instant.now().toString());
        save(spec);
        return spec;
    }

    public ChatSpec appendMessage(String chatId, String role, String content) {
        if (chatId == null || chatId.isBlank()) return null;
        ChatSpec spec = get(chatId);
        if (spec == null) spec = getBySessionId(chatId);
        if (spec == null) return null;
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", UUID.randomUUID().toString());
        message.put("role", role);
        message.put("content", content != null ? content : "");
        message.put("created_at", Instant.now().toString());
        spec.getMessages().add(message);
        spec.setLastMessage(content);
        spec.setUpdatedAt(Instant.now().toString());
        save(spec);
        return spec;
    }

    public ChatSpec getBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        return list().stream()
                .filter(spec -> sessionId.equals(spec.getSessionId()))
                .findFirst()
                .orElse(null);
    }

    // ======================== Internal ========================

    private void save(ChatSpec spec) {
        Path file = chatFilePath(spec.getId());
        JsonUtils.save(file, spec);
    }

    private Path chatFilePath(String chatId) {
        ensureDir();
        String fileName = sanitizeFileName(chatId) + ".json";
        return SafePathUtil.resolveSafe(chatsDir, fileName);
    }

    private void ensureDir() {
        try {
            Files.createDirectories(chatsDir);
        } catch (java.io.IOException e) {
            log.error("Failed to create chats directory: {}", chatsDir, e);
        }
    }

    /**
     * 消毒文件名: 仅保留字母、数字、连字符、下划线.
     */
    static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
