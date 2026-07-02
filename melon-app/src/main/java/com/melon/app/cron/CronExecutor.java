package com.melon.app.cron;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import com.melon.app.runner.AgentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Cron 执行器. 对应 Python cron/executor.py.
 * <p>
 * 执行定时任务时, 构造 {@link Msg} 并注入到指定 Agent,
 * 通过 {@link AgentRunner} 发起非流式查询.
 */
@Component
public class CronExecutor {

    private static final Logger log = LoggerFactory.getLogger(CronExecutor.class);

    private final AgentRunner agentRunner;

    public CronExecutor(AgentRunner agentRunner) {
        this.agentRunner = agentRunner;
    }

    /**
     * 执行定时任务: 将消息注入到指定 Agent.
     *
     * @param job 定时任务定义
     */
    public void execute(CronManager.CronJob job) {
        String agentId = job.getAgentId();
        String message = job.getMessage();
        if (message == null || message.isBlank()) {
            log.warn("Cron job {} has empty message, skipping", job.getId());
            return;
        }
        log.info("Executing cron job: id={}, agent={}, messageLen={}", job.getId(), agentId, message.length());
        injectMessage(agentId, message, job.getId());
    }

    /**
     * 向 Agent 注入消息 (核心方法).
     * <p>
     * 构造 user 角色的 Msg, 通过 AgentRunner 发起查询.
     * 查询异步执行, 不阻塞调度线程.
     *
     * @param agentId 目标 Agent ID
     * @param message 注入的消息内容
     * @param taskId  任务标识 (用于日志和 sessionId)
     */
    public void injectMessage(String agentId, String message, String taskId) {
        try {
            // 构造消息 (近似 AgentScope API)
            Msg msg = new UserMessage(message);
            List<Msg> msgs = List.of(msg);

            String sessionId = "cron-" + taskId;
            String userId = "cron-system";

            // 非流式查询, 异步订阅结果
            agentRunner.query(agentId, msgs, userId, sessionId, Map.of())
                    .doOnSuccess(reply -> log.info("Cron task {} completed, replyLen={}",
                            taskId, reply != null && reply.getTextContent() != null ? reply.getTextContent().length() : 0))
                    .doOnError(err -> log.error("Cron task {} failed", taskId, err))
                    .subscribe(); // 异步, 不阻塞

        } catch (NoClassDefFoundError | Exception e) {
            // AgentScope API 不可用时回退到日志
            log.warn("AgentScope Msg API unavailable, logging message instead: agent={}, task={}", agentId, taskId);
            log.info("Cron message for agent [{}]: {}", agentId, message);
        }
    }

    /**
     * 便捷方法: 直接注入消息到默认 Agent.
     */
    public void injectMessage(String message) {
        injectMessage("default", message, "adhoc-" + System.currentTimeMillis());
    }
}
