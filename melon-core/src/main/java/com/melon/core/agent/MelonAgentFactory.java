/**
 * @author melon
 */
package com.melon.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
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

        // 4. 构建 HarnessAgent
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(agentConfig.getName())
                .agentId(agentId)
                .model(agentConfig.getActiveModel())
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
                .enableTaskList(agentConfig.getRunning().isTaskListEnabled());

        if (agentConfig.getPlanMode().isEnabled()) {
            builder.enablePlanMode(true);
        }

        if (agentConfig.getRunning().getFallbackModel() != null) {
            builder.fallbackModel(agentConfig.getRunning().getFallbackModel());
        }

        return builder.build();
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
            return List.of("execute", "write_file", "edit_file", "read_file", "grep_files", "glob_files", "list_files");
        }
        return List.of("execute", "write_file", "edit_file");
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
