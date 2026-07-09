package com.melon.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ContextCompactConfig;
import com.melon.core.config.LightContextConfig;
import com.melon.core.config.ToolResultPruningConfig;
import com.melon.core.middleware.*;
import com.melon.core.provider.ProviderManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HarnessAgent 构建工厂. 对应 Python react_agent.py 的 MelonAgent.__init__ + _create_toolkit + _build_sys_prompt + _register_hooks.
 * 构建配置完整的 HarnessAgent 单例, 而非每请求新建.
 */
public class MelonAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(MelonAgentFactory.class);
    private final MultiAgentManager agentManager;
    private final TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback;
    private final List<ToolkitContributor> toolkitContributors;
    private final ProviderManager providerManager;

    public MelonAgentFactory() {
        this(null, null, List.of(), null);
    }

    public MelonAgentFactory(MultiAgentManager agentManager) {
        this(agentManager, null, List.of(), null);
    }

    public MelonAgentFactory(MultiAgentManager agentManager,
                             TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback) {
        this(agentManager, tokenUsageCallback, List.of(), null);
    }

    public MelonAgentFactory(MultiAgentManager agentManager,
                             TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback,
                             List<ToolkitContributor> toolkitContributors) {
        this(agentManager, tokenUsageCallback, toolkitContributors, null);
    }

    public MelonAgentFactory(MultiAgentManager agentManager,
                             TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback,
                             List<ToolkitContributor> toolkitContributors,
                             ProviderManager providerManager) {
        this.agentManager = agentManager;
        this.tokenUsageCallback = tokenUsageCallback;
        this.toolkitContributors = toolkitContributors != null ? List.copyOf(toolkitContributors) : List.of();
        this.providerManager = providerManager;
    }

    /**
     * 构建一个配置完整的 HarnessAgent 实例.
     */
    public HarnessAgent create(String agentId, AgentConfig agentConfig, Path workspaceDir,
                                AgentStateStore stateStore) {
        String activeModel = agentConfig.getActiveModel();
        if (activeModel == null || activeModel.isBlank()) {
            throw new IllegalStateException("Model not configured");
        }
        log.info("Building HarnessAgent: name={}, model={}, workspace={}",
                agentConfig.getName(), activeModel, workspaceDir);

        // 1. 构建中间件链 (对应 Python Mixin + hooks)
        List<MiddlewareBase> middlewares = buildMiddlewares(agentId, agentConfig, workspaceDir);

        // 2. 构建压缩配置
        CompactionConfig compaction = buildCompactionConfig(agentConfig.getContextCompact());
        ToolResultEvictionConfig toolResultEviction = buildToolResultEvictionConfig(agentConfig);

        // 3. 构建记忆配置
        MemoryConfig memoryConfig = buildMemoryConfig(agentConfig);

        // 4. 注册 melonPaw 命名工具，避免 Harness 默认 execute/grep_files/glob_files 名称进入前端历史。
        Toolkit toolkit = buildToolkit(agentId, agentConfig, workspaceDir);

        // 4. 构建 HarnessAgent
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(agentConfig.getName())
                .agentId(agentId)
                .toolkit(toolkit)
                .workspace(workspaceDir)
                .filesystem(new LocalFilesystemSpec()
                        .project(Path.of(System.getProperty("user.dir")))
                        .projectWritable(false)
                        .inheritEnv(true)
                        .executeTimeoutSeconds(shellTimeoutSeconds(agentConfig)))
                .middlewares(middlewares)
                .compaction(compaction)
                .toolResultEviction(toolResultEviction)
                .memory(memoryConfig)
                .stateStore(stateStore)
                .maxIters(agentConfig.getRunning().getMaxIters())
                .maxRetries(agentConfig.getRunning().isLlmRetryEnabled() ? agentConfig.getRunning().getLlmMaxRetries() : 0)
                .modelExecutionConfig(modelExecutionConfig(agentConfig))
                .toolExecutionConfig(toolExecutionConfig(agentConfig))
                .permissionContext(buildPermissionContext(agentConfig))
                .enableMetaTool(true)
                .enableSkillManageTool(SkillManageConfig.defaults())
                .enableTaskList(agentConfig.getRunning().isTaskListEnabled())
                .disableShellTool()
                .disableFilesystemTools();
        if (providerManager != null) {
            builder.model(providerManager.createModel(activeModel));
        } else {
            builder.model(activeModel);
        }

        if (agentConfig.getPlanMode().isEnabled()) {
            builder.enablePlanMode(true);
        }

        if (agentConfig.getRunning().getFallbackModel() != null) {
            builder.fallbackModel(agentConfig.getRunning().getFallbackModel());
        }

        return builder.build();
    }

    private Toolkit buildToolkit(String agentId, AgentConfig config, Path workspaceDir) {
        Toolkit toolkit = new Toolkit();
        registerIfEnabled(toolkit, config, "execute_shell_command", "com.melon.tools.shell.ExecuteShellCommandTool",
                workspaceDir.toString(),
                config.getRunning().getShellCommandTimeout(),
                config.getRunning().getShellCommandExecutable());
        registerIfEnabled(toolkit, config, "read_file", "com.melon.tools.fileio.ReadFileTool", workspaceDir.toString());
        registerIfEnabled(toolkit, config, "write_file", "com.melon.tools.fileio.WriteFileTool", workspaceDir.toString());
        registerIfEnabled(toolkit, config, "edit_file", "com.melon.tools.fileio.EditFileTool", workspaceDir.toString());
        registerIfEnabled(toolkit, config, "grep_search", "com.melon.tools.fileio.GrepSearchTool", workspaceDir.toString());
        registerIfEnabled(toolkit, config, "glob_search", "com.melon.tools.fileio.GlobSearchTool", workspaceDir.toString());
        registerIfEnabled(toolkit, config, "browser_use", "com.melon.tools.browser.BrowserUseTool");
        registerIfEnabled(toolkit, config, "view_image", "com.melon.tools.media.ViewImageTool", workspaceDir);
        registerIfEnabled(toolkit, config, "view_video", "com.melon.tools.media.ViewVideoTool", workspaceDir);
        registerIfEnabled(toolkit, config, "desktop_screenshot", "com.melon.tools.media.DesktopScreenshotTool", workspaceDir.resolve("screenshots"));
        registerIfEnabled(toolkit, config, "get_current_time", "com.melon.tools.util.GetCurrentTimeTool");
        registerIfEnabled(toolkit, config, "set_user_timezone", "com.melon.tools.util.SetUserTimezoneTool");
        registerIfEnabled(toolkit, config, "get_token_usage", "com.melon.tools.util.GetTokenUsageTool");
        registerIfEnabled(toolkit, config, "send_file_to_user", "com.melon.tools.util.SendFileToUserTool", workspaceDir.toString());
        registerIfEnabled(toolkit, config, "create_cron_job", "com.melon.tools.cron.CreateCronJobTool", workspaceDir);
        registerIfEnabled(toolkit, config, "list_agents", "com.melon.tools.agent.ListAgentsTool", agentManager);
        registerIfEnabled(toolkit, config, "chat_with_agent", "com.melon.tools.agent.ChatWithAgentTool");
        registerIfEnabled(toolkit, config, "submit_to_agent", "com.melon.tools.agent.SubmitToAgentTool");
        registerIfEnabled(toolkit, config, "check_agent_task", "com.melon.tools.agent.CheckAgentTaskTool");
        registerIfEnabled(toolkit, config, "spawn_subagent", "com.melon.tools.agent.SpawnSubagentTool");
        registerIfEnabled(toolkit, config, "delegate_external_agent", "com.melon.tools.agent.DelegateExternalAgentTool", workspaceDir);
        registerIfEnabled(toolkit, config, "materialize_skill", "com.melon.tools.skill.MaterializeSkillTool", workspaceDir);
        for (ToolkitContributor contributor : toolkitContributors) {
            try {
                contributor.contribute(agentId, config, workspaceDir, toolkit);
            } catch (Exception e) {
                log.warn("Toolkit contributor {} failed for agent {}", contributor.getClass().getName(), agentId, e);
            }
        }
        log.info("melonPaw toolkit registered tools: {}", toolkit.getToolNames());
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
    private List<MiddlewareBase> buildMiddlewares(String agentId, AgentConfig config, Path workspaceDir) {
        List<MiddlewareBase> list = new ArrayList<>();

        // 1. 系统提示词中间件 (对应 _build_sys_prompt)
        boolean heartbeatEnabled = config.getHeartbeat() != null && config.getHeartbeat().isEnabled();
        list.add(new SystemPromptMiddleware(workspaceDir, config.getSystemPromptFiles(), heartbeatEnabled, false));

        // 2. 媒体过滤中间件 (对应 _reasoning 中的媒体过滤)
        list.add(new MediaFilterMiddleware());

        // 3. 自动续推中间件 (对应 _auto_continue_if_text_only)
        if (config.getRunning().isAutoContinueOnTextOnly()) {
            list.add(new AutoContinueMiddleware());
        }

        // 4. Coding Mode 中间件
        if (config.getCodingMode().isEnabled()) {
            list.add(new CodingModeMiddleware(config.getCodingMode()));
        }

        // 5. Token 记录中间件 (对应 TokenRecordingModelWrapper)
        list.add(new TokenRecordingMiddleware((ignoredAgentName, sessionId, modelName, usage, latencyMs) -> {
            if (tokenUsageCallback != null) {
                String modelId = config.getActiveModel() != null && !config.getActiveModel().isBlank()
                        ? config.getActiveModel()
                        : modelName;
                tokenUsageCallback.record(agentId, sessionId, modelId, usage, latencyMs);
            }
        }));

        // HarnessAgent 会注册官方 file-backed memory_search/memory_get/session_search 工具。
        // Java 侧尚未接入 Python 的 ReMeLight/ADBPG 语义记忆后端，所以这里不再注入自定义假 memory_search。

        return list;
    }

    private ExecutionConfig modelExecutionConfig(AgentConfig config) {
        int attempts = config.getRunning().isLlmRetryEnabled()
                ? Math.max(1, config.getRunning().getLlmMaxRetries() + 1)
                : 1;
        return ExecutionConfig.builder()
                .timeout(Duration.ofMinutes(5))
                .maxAttempts(attempts)
                .initialBackoff(Duration.ofMillis(Math.max(100, Math.round(config.getRunning().getLlmBackoffBase() * 1000))))
                .maxBackoff(Duration.ofMillis(Math.max(500, Math.round(config.getRunning().getLlmBackoffCap() * 1000))))
                .backoffMultiplier(2.0)
                .retryOn(config.getRunning().isLlmRetryEnabled() ? ExecutionConfig.RETRYABLE_ERRORS : ignored -> false)
                .build();
    }

    private ExecutionConfig toolExecutionConfig(AgentConfig config) {
        return ExecutionConfig.builder()
                .timeout(Duration.ofMillis(Math.max(1000, Math.round(config.getRunning().getShellCommandTimeout() * 1000))))
                .maxAttempts(1)
                .build();
    }

    private PermissionContextState buildPermissionContext(AgentConfig config) {
        String level = config.getApproval() != null ? config.getApproval().getLevel() : "AUTO";
        if ("OFF".equalsIgnoreCase(level)) {
            return PermissionContextState.builder().mode(PermissionMode.BYPASS).build();
        }
        boolean strict = "STRICT".equalsIgnoreCase(level);
        boolean smart = "SMART".equalsIgnoreCase(level);
        PermissionContextState.Builder builder = PermissionContextState.builder()
                .mode(strict ? PermissionMode.DEFAULT : PermissionMode.BYPASS);
        if (strict) {
            for (String tool : strictApprovalTools()) {
                builder.addAskRule(tool, new PermissionRule(tool, null, PermissionBehavior.ASK, "melonpaw-java"));
            }
        } else {
            builder.addAskRule("execute_shell_command", new PermissionRule("execute_shell_command",
                    "delete", PermissionBehavior.ASK, "melonpaw-java"));
            builder.addAskRule("execute_shell_command", new PermissionRule("execute_shell_command",
                    "qwenpaw-dangerous-shell", PermissionBehavior.ASK, "melonpaw-java"));
            for (String tool : lifecycleApprovalTools()) {
                builder.addAskRule(tool, new PermissionRule(tool, null, PermissionBehavior.ASK, "melonpaw-java"));
            }
            if (smart) {
                for (String tool : smartApprovalTools()) {
                    builder.addAskRule(tool, new PermissionRule(tool, null, PermissionBehavior.ASK, "melonpaw-java"));
                }
            }
        }
        return builder.build();
    }

    private List<String> lifecycleApprovalTools() {
        return List.of(
                "create_cron_job",
                "delegate_external_agent",
                "skill_manage",
                "propose_skill",
                "task_cancel",
                "plan_exit"
        );
    }

    private List<String> smartApprovalTools() {
        return List.of(
                "execute_shell_command",
                "write_file",
                "edit_file",
                "browser_use",
                "spawn_subagent",
                "submit_to_agent",
                "chat_with_agent",
                "agent_spawn",
                "agent_send"
        );
    }

    private List<String> strictApprovalTools() {
        java.util.LinkedHashSet<String> tools = new java.util.LinkedHashSet<>();
        tools.addAll(List.of("execute_shell_command", "write_file", "edit_file", "read_file", "grep_search", "glob_search"));
        tools.addAll(lifecycleApprovalTools());
        tools.addAll(smartApprovalTools());
        tools.addAll(List.of("materialize_skill", "check_agent_task", "task_output", "task_list", "memory_search", "memory_get", "session_search"));
        return List.copyOf(tools);
    }

    /**
     * 构建压缩配置. 对应 Python ContextCompactConfig.
     */
    private CompactionConfig buildCompactionConfig(ContextCompactConfig py) {
        if (!py.isEnabled()) {
            return CompactionConfig.builder().triggerMessages(0).triggerTokens(0).build();
        }
        int maxInputTokens = 128 * 1024;
        int triggerTokens = (int) Math.max(1, Math.round(maxInputTokens * py.getCompactThresholdRatio()));
        int keepTokens = (int) Math.max(1, Math.round(maxInputTokens * py.getReserveThresholdRatio()));
        CompactionConfig.Builder builder = CompactionConfig.builder()
                .triggerMessages(py.getTriggerMessages())
                .triggerTokens(triggerTokens)
                .keepMessages(py.getKeepMessages())
                .keepTokens(keepTokens)
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
     * 构建单条工具结果驱逐配置. 对应 AgentScope 2.0 官方 ToolResultEvictionMiddleware.
     */
    private ToolResultEvictionConfig buildToolResultEvictionConfig(AgentConfig config) {
        ToolResultEvictionConfig.Builder builder = ToolResultEvictionConfig.builder();

        LightContextConfig light = config.getLightContextConfig();
        ToolResultPruningConfig pruning = light != null ? light.getToolResultPruningConfig() : null;
        if (pruning != null && pruning.isEnabled()) {
            int maxChars = firstPositive(pruning.getPruningOldMsgMaxBytes(), pruning.getPruningRecentMsgMaxBytes());
            if (maxChars > 0) {
                builder.maxResultChars(maxChars);
                builder.previewChars(Math.min(ToolResultEvictionConfig.DEFAULT_PREVIEW_CHARS,
                        Math.max(500, maxChars / 10)));
            }
        }

        Set<String> excluded = new LinkedHashSet<>(ToolResultEvictionConfig.DEFAULT_EXCLUDED_TOOLS);
        excluded.add("grep_search");
        excluded.add("glob_search");
        if (pruning != null && pruning.getExemptToolNames() != null) {
            excluded.addAll(pruning.getExemptToolNames());
        }
        builder.excludedToolNames(excluded);
        return builder.build();
    }

    private int firstPositive(int first, int second) {
        if (first > 0) return first;
        if (second > 0) return second;
        return 0;
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
