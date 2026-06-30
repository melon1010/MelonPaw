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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 安全 JSON 会话存储. 对应 Python app/runner/session.py.
 * <p>
 * 提供文件名消毒 (防路径遍历) 和跨平台兼容的 JSON 会话存储.
 * 会话文件存储在 ~/.melon/sessions/ 目录.
 */
@Component
public class SafeJSONSession {

    private static final Logger log = LoggerFactory.getLogger(SafeJSONSession.class);

    private final Path sessionsDir;

    public SafeJSONSession(ConfigManager configManager) {
        this.sessionsDir = configManager.resolveHomeDir().resolve("sessions");
    }

    // ======================== Public API ========================

    /**
     * 保存会话数据到 JSON 文件.
     *
     * @param sessionId 会话 ID (会经过消毒处理)
     * @param data      会话数据
     */
    public void save(String sessionId, Map<String, Object> data) {
        Path file = sessionFilePath(sessionId);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("sessionId", sessionId);
        wrapper.put("data", data);
        JsonUtils.save(file, wrapper);
        log.debug("Session saved: id={}, file={}", sessionId, file);
    }

    /**
     * 加载会话数据.
     *
     * @param sessionId 会话 ID
     * @return 会话数据 Map, 不存在则返回空 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> load(String sessionId) {
        Path file = sessionFilePath(sessionId);
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> wrapper = JsonUtils.loadAsMap(file);
        Object data = wrapper.get("data");
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return new LinkedHashMap<>(wrapper);
    }

    /**
     * 清除指定会话文件.
     *
     * @param sessionId 会话 ID
     * @return true 如果成功删除
     */
    public boolean clear(String sessionId) {
        Path file = sessionFilePath(sessionId);
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                log.info("Session cleared: id={}", sessionId);
            }
            return deleted;
        } catch (java.io.IOException e) {
            log.error("Failed to clear session: id={}", sessionId, e);
            return false;
        }
    }

    /**
     * 检查会话是否存在.
     */
    public boolean exists(String sessionId) {
        return Files.exists(sessionFilePath(sessionId));
    }

    // ======================== Internal ========================

    private Path sessionFilePath(String sessionId) {
        ensureDir();
        String safeName = sanitizeFileName(sessionId);
        String fileName = safeName + ".session.json";
        return SafePathUtil.resolveSafe(sessionsDir, fileName);
    }

    private void ensureDir() {
        try {
            Files.createDirectories(sessionsDir);
        } catch (java.io.IOException e) {
            log.error("Failed to create sessions directory: {}", sessionsDir, e);
        }
    }

    /**
     * 消毒文件名: 移除路径分隔符和危险字符, 防止路径遍历.
     * <p>
     * 移除 .. / \ 和空字符, 仅保留字母数字连字符下划线点.
     */
    static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "default";
        }
        // 移除路径遍历序列
        String cleaned = name.replace("..", "").replace("/", "").replace("\\", "");
        // 移除空字符
        cleaned = cleaned.replace("\0", "");
        // 仅保留安全字符
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9_\\-\\.]", "_");
        if (cleaned.isBlank()) {
            return "default";
        }
        if (cleaned.length() > 128) {
            cleaned = cleaned.substring(0, 128);
        }
        return cleaned;
    }
}
