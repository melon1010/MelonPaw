/**
 * @author melon
 */
package com.melon.app.runner;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查询错误转储. 对应 Python app/runner/query_error_dump.py.
 * <p>
 * 查询失败时保存错误上下文到 ~/.melon/dumps/ 目录, 便于事后排查.
 * 包含: agentId, sessionId, query, error, timestamp, stackTrace.
 */
@Component
public class QueryErrorDump {

    private static final Logger log = LoggerFactory.getLogger(QueryErrorDump.class);

    private final Path dumpsDir;

    public QueryErrorDump(ConfigManager configManager) {
        this.dumpsDir = configManager.resolveHomeDir().resolve("dumps");
    }

    /**
     * 转储查询错误上下文.
     *
     * @param agentId   Agent ID
     * @param sessionId 会话 ID
     * @param query     原始查询内容
     * @param error     异常对象
     * @return 转储文件路径, 或 null 如果失败
     */
    public String dump(String agentId, String sessionId, String query, Throwable error) {
        try {
            ensureDir();

            String timestamp = Instant.now().toString();
            String fileName = buildFileName(agentId, timestamp);
            Path file = dumpsDir.resolve(fileName);

            Map<String, Object> dump = new LinkedHashMap<>();
            dump.put("agentId", agentId);
            dump.put("sessionId", sessionId);
            dump.put("query", query);
            dump.put("error", error != null ? error.toString() : "unknown");
            dump.put("errorType", error != null ? error.getClass().getName() : "null");
            dump.put("errorMessage", error != null ? error.getMessage() : "null");
            dump.put("stackTrace", error != null ? toStackTraceString(error) : "");
            dump.put("timestamp", timestamp);

            JsonUtils.save(file, dump);
            log.info("Error dump saved: agent={}, session={}, file={}", agentId, sessionId, file);
            return file.toString();
        } catch (Exception e) {
            log.error("Failed to write error dump for agent={}", agentId, e);
            return null;
        }
    }

    /**
     * 转储查询错误上下文 (带自定义上下文数据).
     *
     * @param agentId    Agent ID
     * @param sessionId  会话 ID
     * @param query      原始查询内容
     * @param error      异常对象
     * @param extraContext 额外上下文数据
     * @return 转储文件路径, 或 null 如果失败
     */
    public String dump(String agentId, String sessionId, String query,
                       Throwable error, Map<String, Object> extraContext) {
        try {
            ensureDir();

            String timestamp = Instant.now().toString();
            String fileName = buildFileName(agentId, timestamp);
            Path file = dumpsDir.resolve(fileName);

            Map<String, Object> dump = new LinkedHashMap<>();
            dump.put("agentId", agentId);
            dump.put("sessionId", sessionId);
            dump.put("query", query);
            dump.put("error", error != null ? error.toString() : "unknown");
            dump.put("errorType", error != null ? error.getClass().getName() : "null");
            dump.put("errorMessage", error != null ? error.getMessage() : "null");
            dump.put("stackTrace", error != null ? toStackTraceString(error) : "");
            dump.put("timestamp", timestamp);
            if (extraContext != null && !extraContext.isEmpty()) {
                dump.put("extraContext", extraContext);
            }

            JsonUtils.save(file, dump);
            log.info("Error dump saved (with context): agent={}, session={}, file={}",
                    agentId, sessionId, file);
            return file.toString();
        } catch (Exception e) {
            log.error("Failed to write error dump for agent={}", agentId, e);
            return null;
        }
    }

    /**
     * 列出所有转储文件.
     */
    public java.util.List<String> listDumps() {
        java.util.List<String> result = new java.util.ArrayList<>();
        java.io.File[] files = dumpsDir.toFile()
                .listFiles((dir, name) -> name.endsWith(".dump.json"));
        if (files != null) {
            for (java.io.File f : files) {
                result.add(f.getName());
            }
        }
        result.sort(String::compareTo);
        return result;
    }

    /**
     * 清除所有转储文件.
     */
    public int clearAll() {
        int count = 0;
        java.io.File[] files = dumpsDir.toFile()
                .listFiles((dir, name) -> name.endsWith(".dump.json"));
        if (files != null) {
            for (java.io.File f : files) {
                if (f.delete()) {
                    count++;
                }
            }
        }
        log.info("Cleared {} error dump files", count);
        return count;
    }

    // ======================== Internal ========================

    private void ensureDir() throws java.io.IOException {
        Files.createDirectories(dumpsDir);
    }

    private String buildFileName(String agentId, String timestamp) {
        String safeAgent = (agentId != null ? agentId : "unknown")
                .replaceAll("[^a-zA-Z0-9_\\-]", "_");
        String safeTime = timestamp.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return safeAgent + "_" + safeTime + ".dump.json";
    }

    private static String toStackTraceString(Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
