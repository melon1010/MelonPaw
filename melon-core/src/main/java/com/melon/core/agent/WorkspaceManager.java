/**
 * @author melon
 */
package com.melon.core.agent;

import com.melon.core.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作区管理器. 对应 Python app/workspace/workspace.py.
 */
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);
    private static final String DEFAULT_AGENTS_MD = "# Agent Instructions\n\nYou are a helpful assistant.\n";

    /**
     * 解析工作区路径.
     */
    public Path resolveWorkspaceDir(AgentConfig config) {
        String dir = config.getWorkspaceDir();
        if (dir.startsWith("~")) {
            dir = dir.replace("~", System.getProperty("user.home"));
        }
        return Path.of(dir);
    }

    /**
     * 解析插件工作区路径. 为插件分配独立的子目录.
     *
     * @param pluginId 插件唯一标识
     * @return 插件工作区路径
     */
    public Path resolveWorkspaceDir(String pluginId) {
        String baseDir = System.getProperty("user.home") + "/.melon/workspace";
        return Path.of(baseDir).resolve("plugins").resolve(pluginId);
    }

    /**
     * 初始化工作区. 创建 AGENTS.md 等基础文件.
     */
    public void initWorkspace(Path dir) {
        try {
            Files.createDirectories(dir);
            Path agentsMd = dir.resolve("AGENTS.md");
            if (!Files.exists(agentsMd)) {
                Files.writeString(agentsMd, DEFAULT_AGENTS_MD);
                log.info("Created default AGENTS.md at {}", agentsMd);
            }
            Files.createDirectories(dir.resolve("agents"));
            Files.createDirectories(dir.resolve("memory"));
            Files.createDirectories(dir.resolve("skills"));
        } catch (IOException e) {
            log.error("Failed to init workspace at {}", dir, e);
        }
    }
}
