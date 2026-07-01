/**
 * @author melon
 */
package com.melon.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;

/**
 * 配置管理器. 对应 Python config.py 的 ConfigManager.
 * 负责加载/保存 YAML 配置, 合并默认值.
 */
public class ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);
    private static final String CONFIG_DIR = ".melon";
    private static final String CONFIG_FILE = "config.yaml";

    private final ObjectMapper yamlMapper;
    private Path configPath;
    private MelonConfig config;
    private String defaultHomeDir = "~/.melon";

    public ConfigManager() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.configPath = Path.of(System.getProperty("user.home"), CONFIG_DIR, CONFIG_FILE);
        this.config = mergeDefaults(new MelonConfig());
    }

    public void setConfigPath(Path configPath) {
        this.configPath = configPath;
    }

    public void setHomeDir(String homeDir) {
        this.defaultHomeDir = homeDir == null || homeDir.isBlank() ? "~/.melon" : homeDir;
        config.setHomeDir(homeDir);
    }

    /**
     * 加载配置. 如文件不存在则使用默认值并创建文件.
     */
    public MelonConfig load() {
        if (Files.exists(configPath)) {
            try {
                config = yamlMapper.readValue(configPath.toFile(), MelonConfig.class);
                config = mergeDefaults(config);
                log.info("Config loaded from {}", configPath);
            } catch (IOException e) {
                log.error("Failed to load config, using defaults", e);
                config = mergeDefaults(new MelonConfig());
            }
        } else {
            log.info("Config file not found, creating with defaults at {}", configPath);
            config = mergeDefaults(new MelonConfig());
            save();
        }
        return config;
    }

    /**
     * 原子保存配置.
     */
    public void save() {
        try {
            Files.createDirectories(configPath.getParent());
            Path tmp = configPath.resolveSibling(CONFIG_FILE + ".tmp");
            yamlMapper.writeValue(tmp.toFile(), config);
            Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Config saved to {}", configPath);
        } catch (IOException e) {
            log.error("Failed to save config", e);
        }
    }

    /**
     * 重载配置.
     */
    public void reload() {
        load();
    }

    public MelonConfig getConfig() {
        return config;
    }

    public Path getConfigPath() {
        return configPath;
    }

    /**
     * 合并默认值. 对应 Python _default_builtin_tools() + _merge_default_tools.
     * 确保新工具出现在配置中, 旧条目规范化.
     */
    private MelonConfig mergeDefaults(MelonConfig loaded) {
        if (loaded.getHomeDir() == null || loaded.getHomeDir().isBlank()) {
            loaded.setHomeDir(defaultHomeDir);
        }
        if (loaded.getAgents() == null) {
            loaded.setAgents(Collections.singletonMap("default", new AgentConfig()));
        }
        for (var entry : loaded.getAgents().entrySet()) {
            AgentConfig ac = entry.getValue();
            if (ac.getTools() == null) ac.setTools(new ToolsConfig());
            mergeDefaultTools(ac.getTools());
            if (ac.getRunning() == null) ac.setRunning(new RunningConfig());
            if (ac.getContextCompact() == null) ac.setContextCompact(new ContextCompactConfig());
            if (ac.getCodingMode() == null) ac.setCodingMode(new CodingModeConfig());
            if (ac.getPlanMode() == null) ac.setPlanMode(new PlanModeConfig());
            if (ac.getApproval() == null) ac.setApproval(new AgentConfig.ApprovalConfig());
        }
        return loaded;
    }

    private void mergeDefaultTools(ToolsConfig tools) {
        var current = tools.getBuiltinTools();
        if (current == null) {
            tools.setBuiltinTools(new ToolsConfig().getBuiltinTools());
            return;
        }
        new ToolsConfig().getBuiltinTools().forEach(current::putIfAbsent);
        new ToolsConfig().getBuiltinTools().forEach((name, defaults) -> {
            BuiltinToolConfig tool = current.get(name);
            if (tool == null) return;
            if (tool.getName() == null || tool.getName().isBlank()) tool.setName(name);
            if (tool.getDescription() == null || tool.getDescription().isBlank()) {
                tool.setDescription(defaults.getDescription());
            }
            if (tool.getIcon() == null || tool.getIcon().isBlank()) tool.setIcon(defaults.getIcon());
            if (!defaults.isDisplayToUser()) tool.setDisplayToUser(false);
        });
        migrateTool(current, "execute", "execute_shell_command");
        migrateTool(current, "grep_files", "grep_search");
        migrateTool(current, "glob_files", "glob_search");
        for (String legacy : java.util.List.of(
                "execute", "grep_files", "glob_files", "list_files",
                "append_file", "memory_search", "memory_get",
                "session_search", "session_list", "session_history",
                "skill_manage", "propose_skill", "load_skill_through_path",
                "materialize_skill", "reset_equipped_tools",
                "plan_enter", "plan_write", "plan_exit",
                "task", "task_output")) {
            BuiltinToolConfig tool = current.get(legacy);
            if (tool != null) {
                tool.setEnabled(false);
                tool.setDisplayToUser(false);
            }
        }
    }

    private void migrateTool(java.util.Map<String, BuiltinToolConfig> tools, String from, String to) {
        BuiltinToolConfig source = tools.get(from);
        BuiltinToolConfig target = tools.get(to);
        if (source == null || target == null) return;
        target.setEnabled(source.isEnabled());
        target.setAsyncExecution(source.isAsyncExecution());
        if ((target.getDescription() == null || target.getDescription().isBlank())
                && source.getDescription() != null) {
            target.setDescription(source.getDescription());
        }
    }

    public Path resolveHomeDir() {
        return expandPath(config.getHomeDir() == null || config.getHomeDir().isBlank()
                ? "~/.melon"
                : config.getHomeDir());
    }

    public Path resolveWorkspaceDir(String agentId) {
        String id = agentId == null || agentId.isBlank() ? "default" : agentId;
        AgentConfig agent = config.getAgents().get(id);
        if (agent != null && agent.getWorkspaceDir() != null && !agent.getWorkspaceDir().isBlank()) {
            return expandPath(agent.getWorkspaceDir());
        }
        return resolveWorkspaceRootDir().resolve(id).normalize();
    }

    public Path resolveWorkspaceRootDir() {
        if (config.getPaths() != null
                && config.getPaths().getWorkspaceRoot() != null
                && !config.getPaths().getWorkspaceRoot().isBlank()) {
            return expandPath(config.getPaths().getWorkspaceRoot()).normalize();
        }
        return resolveHomeDir().resolve("workspaces").normalize();
    }

    public Path resolveSkillPoolDir() {
        if (config.getPaths() != null
                && config.getPaths().getSkillPoolDir() != null
                && !config.getPaths().getSkillPoolDir().isBlank()) {
            return expandPath(config.getPaths().getSkillPoolDir());
        }
        return resolveHomeDir().resolve("skill_pool").normalize();
    }

    public Path resolveStateDir() {
        if (config.getState() != null
                && config.getState().getBaseDir() != null
                && !config.getState().getBaseDir().isBlank()) {
            return expandPath(config.getState().getBaseDir());
        }
        return resolveHomeDir().resolve("state").normalize();
    }

    public String stateStoreType() {
        return config.getState() == null || config.getState().getStore() == null || config.getState().getStore().isBlank()
                ? "json_file"
                : config.getState().getStore();
    }

    private Path expandPath(String path) {
        String value = path;
        if (value.startsWith("~")) {
            value = System.getProperty("user.home") + value.substring(1);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }
}
