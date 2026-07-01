package com.melon.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ContextCompactConfig;
import com.melon.core.middleware.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HarnessAgent 构建工厂. 对应 Python react_agent.py 的 MelonAgent.__init__ + _create_toolkit + _build_sys_prompt + _register_hooks.
 * 构建配置完整的 HarnessAgent 单例, 而非每请求新建.
 */
public class MelonAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(MelonAgentFactory.class);
    private final MultiAgentManager agentManager;

    public MelonAgentFactory() {
        this(null);
    }

    public MelonAgentFactory(MultiAgentManager agentManager) {
        this.agentManager = agentManager;
    }

    /**
     * 构建一个配置完整的 HarnessAgent 实例.
     */
    public HarnessAgent create(String agentId, AgentConfig agentConfig, Path workspaceDir,
                                AgentStateStore stateStore) {
        log.info("Building HarnessAgent: name={}, model={}, workspace={}",
                agentConfig.getName(), agentConfig.getActiveModel(), workspaceDir);

        // 1. 构建中间件链 (对应 Python Mixin + hooks)
        List<MiddlewareBase> middlewares = buildMiddlewares(agentConfig, workspaceDir);

        // 2. 构建压缩配置
        CompactionConfig compaction = buildCompactionConfig(agentConfig.getContextCompact());

        // 3. 构建记忆配置
        MemoryConfig memoryConfig = buildMemoryConfig(agentConfig);

        // 4. 注册 QwenPaw 命名工具，避免 Harness 默认 execute/grep_files/glob_files 名称进入前端历史。
        Toolkit toolkit = buildToolkit(agentConfig, workspaceDir);

        // 4. 构建 HarnessAgent
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(agentConfig.getName())
                .agentId(agentId)
                .model(agentConfig.getActiveModel())
                .toolkit(toolkit)
                .workspace(workspaceDir)
                .filesystem(new LocalFilesystemSpec()
                        .project(Path.of(System.getProperty("user.dir")))
                        .projectWritable(false)
                        .inheritEnv(true)
                        .executeTimeoutSeconds(shellTimeoutSeconds(agentConfig)))
                .middlewares(middlewares)
                .compaction(compaction)
                .memory(memoryConfig)
                .stateStore(stateStore)
                .maxIters(agentConfig.getRunning().getMaxIters())
                .maxRetries(agentConfig.getRunning().getLlmMaxRetries())
                .permissionContext(buildPermissionContext(agentConfig))
                .enableMetaTool(true)
                .enableSkillManageTool(SkillManageConfig.defaults())
                .enableTaskList(agentConfig.getRunning().isTaskListEnabled())
                .disableShellTool()
                .disableFilesystemTools();

        if (agentConfig.getPlanMode().isEnabled()) {
            builder.enablePlanMode(true);
        }

        if (agentConfig.getRunning().getFallbackModel() != null) {
            builder.fallbackModel(agentConfig.getRunning().getFallbackModel());
        }

        return builder.build();
    }

    private Toolkit buildToolkit(AgentConfig config, Path workspaceDir) {
        Toolkit toolkit = new Toolkit();
        registerIfEnabled(toolkit, config, "execute_shell_command", "com.melon.tools.shell.ExecuteShellCommandTool");
        registerIfEnabled(toolkit, config, "read_file", "com.melon.tools.fileio.ReadFileTool", workspaceDir.toString());
        registerIfEnabled(toolkit, config, "write_file", "com.melon.tools.fileio.WriteFileTool");
        registerIfEnabled(toolkit, config, "edit_file", "com.melon.tools.fileio.EditFileTool");
        registerIfEnabled(toolkit, config, "grep_search", "com.melon.tools.fileio.GrepSearchTool");
        registerIfEnabled(toolkit, config, "glob_search", "com.melon.tools.fileio.GlobSearchTool");
        registerIfEnabled(toolkit, config, "browser_use", "com.melon.tools.browser.BrowserUseTool");
        registerIfEnabled(toolkit, config, "view_image", "com.melon.tools.media.ViewImageTool", workspaceDir);
        registerIfEnabled(toolkit, config, "view_video", "com.melon.tools.media.ViewVideoTool", workspaceDir);
        registerIfEnabled(toolkit, config, "desktop_screenshot", "com.melon.tools.media.DesktopScreenshotTool", workspaceDir.resolve("screenshots"));
        registerIfEnabled(toolkit, config, "get_current_time", "com.melon.tools.util.GetCurrentTimeTool");
        registerIfEnabled(toolkit, config, "set_user_timezone", "com.melon.tools.util.SetUserTimezoneTool");
        registerIfEnabled(toolkit, config, "get_token_usage", "com.melon.tools.util.GetTokenUsageTool");
        registerIfEnabled(toolkit, config, "send_file_to_user", "com.melon.tools.util.SendFileToUserTool");
        registerIfEnabled(toolkit, config, "create_cron_job", "com.melon.tools.cron.CreateCronJobTool", workspaceDir);
        registerIfEnabled(toolkit, config, "list_agents", "com.melon.tools.agent.ListAgentsTool", agentManager);
        registerIfEnabled(toolkit, config, "chat_with_agent", "com.melon.tools.agent.ChatWithAgentTool");
        registerIfEnabled(toolkit, config, "submit_to_agent", "com.melon.tools.agent.SubmitToAgentTool");
        registerIfEnabled(toolkit, config, "check_agent_task", "com.melon.tools.agent.CheckAgentTaskTool");
        registerIfEnabled(toolkit, config, "spawn_subagent", "com.melon.tools.agent.SpawnSubagentTool");
        registerIfEnabled(toolkit, config, "delegate_external_agent", "com.melon.tools.agent.DelegateExternalAgentTool", workspaceDir);
        log.info("QwenPaw toolkit registered tools: {}", toolkit.getToolNames());
        return toolkit;
    }

    private void registerIfEnabled(Toolkit toolkit, AgentConfig config, String toolName, String className, Object... args) {
        if (!isEnabled(config, toolName)) return;
        try {
            Class<?> type = Class.forName(className);
            Object tool = newInstance(type, args);
            toolkit.registerTool(tool);
        } catch (ClassNotFoundException e) {
            log.debug("Tool implementation not on classpath: {}", className);
        } catch (Exception e) {
            log.warn("Failed to register tool {} ({})", toolName, className, e);
        }
    }

    private Object newInstance(Class<?> type, Object... args) throws Exception {
        for (Constructor<?> ctor : type.getConstructors()) {
            Class<?>[] parameterTypes = ctor.getParameterTypes();
            if (parameterTypes.length != args.length) continue;
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (args[i] != null && !wrap(parameterTypes[i]).isInstance(args[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) return ctor.newInstance(args);
        }
        return type.getDeclaredConstructor().newInstance();
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        return type;
    }

    private boolean isEnabled(AgentConfig config, String toolName) {
        if (config.getTools() == null || config.getTools().getBuiltinTools() == null) return true;
        var tool = config.getTools().getBuiltinTools().get(toolName);
        return tool == null || tool.isEnabled();
    }

    /**
     * 构建中间件链. 对应 Python 的 Mixin 继承链 + LightContextManager hooks.
     */
    private List<MiddlewareBase> buildMiddlewares(AgentConfig config, Path workspaceDir) {
        List<MiddlewareBase> list = new ArrayList<>();

        // 1. 系统提示词中间件 (对应 _build_sys_prompt)
        list.add(new SystemPromptMiddleware(workspaceDir, config.getSystemPromptFiles()));

        // 2. 媒体过滤中间件 (对应 _reasoning 中的媒体过滤)
        list.add(new MediaFilterMiddleware());

        // 3. 自动续推中间件 (对应 _auto_continue_if_text_only)
        list.add(new AutoContinueMiddleware());

        // 4. Coding Mode 中间件
        if (config.getCodingMode().isEnabled()) {
            list.add(new CodingModeMiddleware(config.getCodingMode()));
        }

        // 5. Token 记录中间件 (对应 TokenRecordingModelWrapper)
        list.add(new TokenRecordingMiddleware());

        // 6. 记忆被动注入中间件 (对应 pre_reply hook)
        list.add(new MemoryInjectionMiddleware());

        return list;
    }

    private PermissionContextState buildPermissionContext(AgentConfig config) {
        String level = config.getApproval() != null ? config.getApproval().getLevel() : "AUTO";
        if ("OFF".equalsIgnoreCase(level) || "AUTO".equalsIgnoreCase(level)) {
            return PermissionContextState.builder().mode(PermissionMode.BYPASS).build();
        }
        PermissionContextState.Builder builder = PermissionContextState.builder().mode(PermissionMode.DEFAULT);
        for (String tool : approvalTools("STRICT".equalsIgnoreCase(level))) {
            builder.addAskRule(tool, new PermissionRule(tool, null, PermissionBehavior.ASK, "qwenpaw-java"));
        }
        return builder.build();
    }

    private List<String> approvalTools(boolean strict) {
        if (strict) {
            return List.of("execute_shell_command", "write_file", "edit_file", "read_file", "grep_search", "glob_search");
        }
        return List.of("execute_shell_command", "write_file", "edit_file");
    }

    /**
     * 构建压缩配置. 对应 Python ContextCompactConfig.
     */
    private CompactionConfig buildCompactionConfig(ContextCompactConfig py) {
        if (!py.isEnabled()) {
            return CompactionConfig.builder().triggerMessages(0).build();
        }
        CompactionConfig.Builder builder = CompactionConfig.builder()
                .triggerMessages(py.getTriggerMessages())
                .keepMessages(py.getKeepMessages())
                .flushBeforeCompact(true)
                .offloadBeforeCompact(true);

        if (py.getSummaryModel() != null) {
            builder.model(py.getSummaryModel());
        }

        // 参数预截断 (Java 独有增强)
        builder.truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
                .maxArgLength(2000)
                .truncationText("... [truncated] ...")
                .build());

        return builder.build();
    }

    /**
     * 构建记忆配置.
     */
    private MemoryConfig buildMemoryConfig(AgentConfig config) {
        return MemoryConfig.builder()
                .flushTrigger(MemoryConfig.FlushTrigger.always())
                .consolidationMaxTokens(4000)
                .consolidationMinGap(java.time.Duration.ofMinutes(30))
                .dailyFileRetentionDays(90)
                .sessionRetentionDays(180)
                .build();
    }

    private int shellTimeoutSeconds(AgentConfig config) {
        int seconds = (int) Math.ceil(config.getRunning().getShellCommandTimeout());
        return Math.max(1, seconds);
    }

}
