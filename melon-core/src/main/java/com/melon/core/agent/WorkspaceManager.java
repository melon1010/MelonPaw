/**
 * @author melon
 */
package com.melon.core.agent;

import com.melon.core.config.AgentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 工作区管理器. 对应 Python app/workspace/workspace.py.
 */
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);
    private static final String DEFAULT_AGENTS_MD = "# Agent Instructions\n\nYou are a helpful assistant.\n";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> WORKSPACE_MD_FILES = List.of(
            "AGENTS.md", "BOOTSTRAP.md", "HEARTBEAT.md", "MEMORY.md", "PROFILE.md", "SOUL.md"
    );
    private static final List<String> WORKSPACE_DIRS = List.of(
            "sessions/console", "media", "tool_results", "skills", "memory",
            "resource", "mem_session", "mem_metadata", "digest", "drivers", ".mcp", "browser"
    );

    /**
     * 解析工作区路径.
     */
    public Path resolveWorkspaceDir(AgentConfig config) {
        String dir = config != null ? config.getWorkspaceDir() : null;
        if (dir == null || dir.isBlank()) {
            throw new IllegalArgumentException("workspace_dir is required");
        }
        if (dir.startsWith("~")) {
            dir = System.getProperty("user.home") + dir.substring(1);
        }
        return Path.of(dir).toAbsolutePath().normalize();
    }

    /**
     * 解析插件工作区路径. 为插件分配独立的子目录.
     *
     * @param pluginId 插件唯一标识
     * @return 插件工作区路径
     */
    public Path resolveWorkspaceDir(String pluginId) {
        String baseDir = System.getProperty("user.home") + "/.melon/workspaces";
        return Path.of(baseDir).resolve("plugins").resolve(pluginId);
    }

    /**
     * 初始化工作区. 创建 AGENTS.md 等基础文件.
     */
    public void initWorkspace(Path dir) {
        try {
            Files.createDirectories(dir);
            for (String filename : WORKSPACE_MD_FILES) {
                createMdIfMissing(dir, filename);
            }
            for (String dirname : WORKSPACE_DIRS) {
                Files.createDirectories(dir.resolve(dirname));
            }
            createJsonIfMissing(dir.resolve("chats.json"), Map.of("version", 1, "chats", List.of()));
            createJsonIfMissing(dir.resolve("jobs.json"), Map.of("version", 1, "jobs", List.of()));
            createJsonIfMissing(dir.resolve("skill.json"), Map.of("version", 1, "skills", List.of()));
            createJsonIfMissing(dir.resolve("agent.json"), Map.of("version", 1));
            createIfMissing(dir.resolve("credentials.yaml"), "");
        } catch (IOException e) {
            log.error("Failed to init workspace at {}", dir, e);
        }
    }

    public void writeAgentJson(Path dir, String agentId, AgentConfig config) {
        try {
            Files.createDirectories(dir);
            Map<String, Object> data = Map.of(
                    "id", agentId,
                    "name", config.getName() != null ? config.getName() : agentId,
                    "workspace_dir", dir.toString(),
                    "active_model", config.getActiveModel() != null ? config.getActiveModel() : "",
                    "system_prompt_files", config.getSystemPromptFiles() != null ? config.getSystemPromptFiles() : List.of(),
                    "tools", config.getTools() != null ? config.getTools() : Map.of()
            );
            JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("agent.json").toFile(), data);
        } catch (IOException e) {
            log.error("Failed to write agent.json at {}", dir, e);
        }
    }

    public boolean deleteWorkspaceIfUnderRoot(Path workspaceDir, Path workspaceRoot) throws IOException {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path target = workspaceDir.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root) || !Files.exists(target)) {
            return false;
        }
        try (var stream = Files.walk(target)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        return true;
    }

    private void createMdIfMissing(Path dir, String filename) throws IOException {
        Path target = dir.resolve(filename);
        try (InputStream in = WorkspaceManager.class.getClassLoader().getResourceAsStream("agents/" + filename)) {
            if (Files.exists(target)) {
                if (in != null && isLegacyBuiltinMd(filename, Files.readString(target))) {
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    log.info("Migrated legacy workspace file {}", target);
                }
                return;
            }
            if (in != null) {
                Files.copy(in, target);
            } else if ("AGENTS.md".equals(filename)) {
                Files.writeString(target, DEFAULT_AGENTS_MD);
            } else {
                Files.writeString(target, "# " + filename + "\n");
            }
            log.info("Created workspace file {}", target);
        }
    }

    private boolean isLegacyBuiltinMd(String filename, String content) {
        String firstLine = content == null ? "" : content.lines().findFirst().orElse("");
        return switch (filename) {
            case "AGENTS.md" -> "# Melon Agent Instructions".equals(firstLine);
            case "BOOTSTRAP.md" -> "# Melon Bootstrap".equals(firstLine);
            case "HEARTBEAT.md" -> "# Melon Heartbeat".equals(firstLine);
            case "MEMORY.md" -> "# Melon Memory System".equals(firstLine);
            case "PROFILE.md" -> "# Melon Agent Profile".equals(firstLine);
            case "SOUL.md" -> "# Melon Personality".equals(firstLine);
            default -> false;
        };
    }

    private void createJsonIfMissing(Path path, Map<String, Object> value) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        log.info("Created workspace file {}", path);
    }

    private void createIfMissing(Path path, String value) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        Files.writeString(path, value);
        log.info("Created workspace file {}", path);
    }
}
