/**
 * @author melon
 */
package com.melon.core.agent;

import com.melon.core.config.*;

import java.util.List;
import java.util.Map;

/**
 * Agent 模板. 对应 Python agents/templates.py.
 * <p>
 * 预置三种模板:
 * <ul>
 *   <li><b>default</b> - 标准全能 Agent, 所有工具, web 访问</li>
 *   <li><b>local</b> - 本地 Agent, 仅文件/shell 工具, 无网络</li>
 *   <li><b>qa</b> - QA/测试 Agent, 代码审查 + 编码模式</li>
 * </ul>
 */
public class AgentTemplate {

    public enum TemplateName {
        DEFAULT,
        LOCAL,
        QA
    }

    private final String name;
    private final String activeModel;
    private final List<String> systemPromptFiles;
    private final List<String> enabledTools;
    private final int maxIters;
    private final boolean planModeEnabled;
    private final String approvalLevel;
    private final boolean codingModeEnabled;
    private final String description;

    // ======================== Template Definitions ========================

    /** 默认模板: 全能 Agent. */
    public static final AgentTemplate DEFAULT = new AgentTemplate(
            "default",
            "dashscope:qwen-plus",
            List.of("AGENTS.md", "SOUL.md", "PROFILE.md"),
            List.of(
                    "execute_shell_command", "read_file", "write_file", "edit_file",
                    "grep_search", "glob_search", "browser_use", "desktop_screenshot",
                    "view_image", "view_video", "send_file_to_user",
                    "get_current_time", "set_user_timezone", "get_token_usage",
                    "list_agents", "chat_with_agent", "submit_to_agent",
                    "check_agent_task", "spawn_subagent"
            ),
            50,
            true,
            "AUTO",
            false,
            "Standard full-featured agent with all tools and web access"
    );

    /** 本地模板: 仅本地工具. */
    public static final AgentTemplate LOCAL = new AgentTemplate(
            "local",
            "dashscope:qwen-turbo",
            List.of("AGENTS.md", "SOUL.md", "PROFILE.md"),
            List.of(
                    "execute_shell_command", "read_file", "write_file", "edit_file",
                    "grep_search", "glob_search",
                    "get_current_time", "set_user_timezone", "get_token_usage"
            ),
            30,
            true,
            "SMART",
            false,
            "Local-only agent without web/browser access, optimized for speed"
    );

    /** QA 模板: 测试 Agent. */
    public static final AgentTemplate QA = new AgentTemplate(
            "qa",
            "dashscope:qwen-plus",
            List.of("AGENTS.md", "SOUL.md", "PROFILE.md"),
            List.of(
                    "execute_shell_command", "read_file", "write_file", "edit_file",
                    "grep_search", "glob_search",
                    "get_current_time", "get_token_usage",
                    "list_agents", "chat_with_agent", "submit_to_agent"
            ),
            100,
            true,
            "AUTO",
            true,
            "QA/testing agent with coding mode enabled, focused on code review and testing"
    );

    /** 所有模板. */
    private static final Map<TemplateName, AgentTemplate> TEMPLATES = Map.of(
            TemplateName.DEFAULT, DEFAULT,
            TemplateName.LOCAL, LOCAL,
            TemplateName.QA, QA
    );

    // ======================== Constructor ========================

    public AgentTemplate(String name, String activeModel, List<String> systemPromptFiles,
                         List<String> enabledTools, int maxIters,
                         boolean planModeEnabled, String approvalLevel,
                         boolean codingModeEnabled, String description) {
        this.name = name;
        this.activeModel = activeModel;
        this.systemPromptFiles = systemPromptFiles;
        this.enabledTools = enabledTools;
        this.maxIters = maxIters;
        this.planModeEnabled = planModeEnabled;
        this.approvalLevel = approvalLevel;
        this.codingModeEnabled = codingModeEnabled;
        this.description = description;
    }

    // ======================== Static API ========================

    /**
     * 获取指定模板.
     */
    public static AgentTemplate get(TemplateName name) {
        return TEMPLATES.get(name);
    }

    /**
     * 按名称获取模板.
     */
    public static AgentTemplate get(String name) {
        try {
            return TEMPLATES.get(TemplateName.valueOf(name.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 列出所有模板.
     */
    public static List<AgentTemplate> list() {
        return List.of(DEFAULT, LOCAL, QA);
    }

    /**
     * 将模板应用到 AgentConfig.
     * <p>
     * 创建一个新的 AgentConfig, 使用模板的预设值.
     *
     * @param templateName 模板名称
     * @return 基于模板的 AgentConfig
     */
    public static AgentConfig apply(TemplateName templateName) {
        AgentTemplate template = TEMPLATES.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template: " + templateName);
        }
        return template.toAgentConfig();
    }

    /**
     * 将模板应用到已有 AgentConfig (覆盖配置).
     */
    public static void applyTo(TemplateName templateName, AgentConfig config) {
        AgentTemplate template = TEMPLATES.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template: " + templateName);
        }
        template.applyTo(config);
    }

    // ======================== Instance Methods ========================

    /**
     * 基于模板创建 AgentConfig.
     */
    public AgentConfig toAgentConfig() {
        AgentConfig config = new AgentConfig();
        applyTo(config);
        return config;
    }

    /**
     * 将模板设置应用到已有 AgentConfig.
     */
    public void applyTo(AgentConfig config) {
        config.setName(name);
        config.setActiveModel(activeModel);
        config.setSystemPromptFiles(List.copyOf(systemPromptFiles));

        // 运行配置
        RunningConfig running = config.getRunning();
        running.setMaxIters(maxIters);

        // Plan 模式
        PlanModeConfig planMode = config.getPlanMode();
        planMode.setEnabled(planModeEnabled);

        // 审批配置
        AgentConfig.ApprovalConfig approval = config.getApproval();
        approval.setLevel(approvalLevel);

        // 编码模式
        CodingModeConfig codingMode = config.getCodingMode();
        codingMode.setEnabled(codingModeEnabled);

        // 工具配置: 启用模板定义的工具, 禁用其余
        ToolsConfig tools = config.getTools();
        Map<String, BuiltinToolConfig> builtinTools = tools.getBuiltinTools();
        // 启用模板定义的工具
        for (String toolName : enabledTools) {
            BuiltinToolConfig tc = builtinTools.get(toolName);
            if (tc != null) {
                tc.setEnabled(true);
            } else {
                builtinTools.put(toolName, new BuiltinToolConfig(toolName, true, ""));
            }
        }
        // 禁用不在模板中的工具
        for (String toolName : builtinTools.keySet()) {
            if (!enabledTools.contains(toolName)) {
                BuiltinToolConfig tc = builtinTools.get(toolName);
                if (tc != null) {
                    tc.setEnabled(false);
                }
            }
        }
    }

    // ======================== Getters ========================

    public String getName() { return name; }
    public String getActiveModel() { return activeModel; }
    public List<String> getSystemPromptFiles() { return systemPromptFiles; }
    public List<String> getEnabledTools() { return enabledTools; }
    public int getMaxIters() { return maxIters; }
    public boolean isPlanModeEnabled() { return planModeEnabled; }
    public String getApprovalLevel() { return approvalLevel; }
    public boolean isCodingModeEnabled() { return codingModeEnabled; }
    public String getDescription() { return description; }
}
