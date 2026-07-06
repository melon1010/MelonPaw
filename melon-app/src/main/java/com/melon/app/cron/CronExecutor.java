package com.melon.app.cron;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import com.melon.app.runner.AgentRunner;
import com.melon.app.runner.ChatManager;
import com.melon.app.service.InboxStore;
import com.melon.channels.ChannelAddress;
import com.melon.channels.ChannelInboundMessage;
import com.melon.channels.ChannelManager;
import com.melon.channels.ChannelOutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
    private final ChatManager chatManager;
    private final ChannelManager channelManager;
    private final InboxStore inboxStore;

    public CronExecutor(AgentRunner agentRunner, ChatManager chatManager, ChannelManager channelManager,
                        InboxStore inboxStore) {
        this.agentRunner = agentRunner;
        this.chatManager = chatManager;
        this.channelManager = channelManager;
        this.inboxStore = inboxStore;
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
        String runId = "cron-" + job.getId() + "-" + System.currentTimeMillis();
        if (job.isSaveResultToInbox()) {
            inboxStore.createTrace(runId, Map.of("job_id", sourceId(job), "job_name", name(job)));
        }
        if (job.getDispatch() != null && !job.getDispatch().isEmpty()) {
            injectDispatchedMessage(agentId, message, runId, job.getDispatch(), out -> {
                appendTraceAndCronEvent(job, runId, cleanText(out), null);
            }, err -> appendTraceAndCronEvent(job, runId, "", err));
        } else {
            injectMessage(agentId, message, runId, reply -> {
                appendTraceAndCronEvent(job, runId, reply != null ? reply.getTextContent() : "", null);
            }, err -> appendTraceAndCronEvent(job, runId, "", err));
        }
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
        injectMessage(agentId, message, taskId, reply -> {}, err -> {});
    }

    public void injectMessage(String agentId, String message, String taskId,
                              Consumer<Msg> onSuccess,
                              Consumer<Throwable> onError) {
        injectMessage(agentId, message, taskId, "default", "cron-" + taskId, "console", Map.of(),
                onSuccess, onError);
    }

    public void injectDispatchedMessage(String agentId, String message, String taskId,
                                        Map<String, Object> dispatch,
                                        Consumer<ChannelOutboundMessage> onSuccess,
                                        Consumer<Throwable> onError) {
        try {
            ChannelInboundMessage inbound = cronInbound(agentId, message, taskId, dispatch);
            channelManager.enqueue(inbound, 20)
                    .whenComplete((out, err) -> {
                        if (err != null) {
                            log.error("Cron task {} channel dispatch failed", taskId, err);
                            onError.accept(err);
                        } else {
                            log.info("Cron task {} channel dispatch completed, replyLen={}",
                                    taskId, out != null && out.getText() != null ? out.getText().length() : 0);
                            onSuccess.accept(out);
                        }
                    });
        } catch (Exception e) {
            log.error("Cron task {} channel dispatch failed before enqueue", taskId, e);
            onError.accept(e);
        }
    }

    public void injectMessage(String agentId, String message, String taskId,
                              String userId, String sessionId, String channel, Map<String, Object> envInfo,
                              Consumer<Msg> onSuccess,
                              Consumer<Throwable> onError) {
        try {
            // 构造消息 (近似 AgentScope API)
            Msg msg = new UserMessage(message);
            List<Msg> msgs = List.of(msg);

            String sid = sessionId == null || sessionId.isBlank() ? "cron-" + taskId : sessionId;
            String uid = userId == null || userId.isBlank() ? "default" : userId;
            String ch = channel == null || channel.isBlank() ? "console" : channel;
            Map<String, Object> env = envInfo != null ? envInfo : Map.of();
            chatManager.getOrCreateForSession(agentId, sid, uid, ch, "cron result: " + taskId);

            // 非流式查询, 异步订阅结果
            agentRunner.query(agentId, msgs, uid, sid, env)
                    .doOnSuccess(reply -> log.info("Cron task {} completed, replyLen={}",
                            taskId, reply != null && reply.getTextContent() != null ? reply.getTextContent().length() : 0))
                    .doOnSuccess(reply -> {
                        chatManager.saveSessionShadowFromStateStore(agentId, ch, uid, sid);
                        appendHeartbeatEvent(agentId, taskId, reply != null ? reply.getTextContent() : "", null);
                        onSuccess.accept(reply);
                    })
                    .doOnError(err -> log.error("Cron task {} failed", taskId, err))
                    .doOnError(err -> {
                        chatManager.saveSessionShadowFromStateStore(agentId, ch, uid, sid);
                        appendHeartbeatEvent(agentId, taskId, "", err);
                        onError.accept(err);
                    })
                    .subscribe(); // 异步, 不阻塞

        } catch (NoClassDefFoundError | Exception e) {
            // AgentScope API 不可用时回退到日志
            log.warn("AgentScope Msg API unavailable, logging message instead: agent={}, task={}", agentId, taskId);
            log.info("Cron message for agent [{}]: {}", agentId, message);
            onError.accept(e);
        }
    }

    private void appendTraceAndCronEvent(CronManager.CronJob job, String runId, String text, Throwable err) {
        if (!job.isSaveResultToInbox()) {
            return;
        }
        if (err != null) {
            inboxStore.appendTraceText(runId, "assistant", err.getMessage());
            inboxStore.finalizeTrace(runId, "error", err.getMessage());
        } else {
            inboxStore.appendTraceText(runId, "assistant", text);
            inboxStore.finalizeTrace(runId, "success", null);
        }
        boolean ok = err == null;
        inboxStore.appendEvent(
                job.getAgentId(),
                "cron",
                sourceId(job),
                ok ? "cron_result" : "cron_error",
                ok ? "success" : "error",
                ok ? "info" : "error",
                (ok ? "Cron result: " : "Cron failed: ") + name(job),
                ok ? preview(text, "Agent cron task finished successfully.") : preview(err.getMessage(), "Cron task failed."),
                Map.of("job_id", sourceId(job), "job_name", name(job), "task_type", value(job.getTaskType(), "agent"), "run_id", runId)
        );
    }

    private void appendHeartbeatEvent(String agentId, String runId, String text, Throwable err) {
        if (runId == null || !runId.startsWith("heartbeat-")) {
            return;
        }
        if (err != null) {
            inboxStore.createTrace(runId, Map.of("source", "heartbeat"));
            inboxStore.appendTraceText(runId, "assistant", err.getMessage());
            inboxStore.finalizeTrace(runId, "error", err.getMessage());
        } else {
            inboxStore.createTrace(runId, Map.of("source", "heartbeat"));
            inboxStore.appendTraceText(runId, "assistant", text);
            inboxStore.finalizeTrace(runId, "success", null);
        }
        boolean ok = err == null;
        inboxStore.appendEvent(
                agentId,
                "heartbeat",
                "heartbeat",
                ok ? "heartbeat_result" : "heartbeat_error",
                ok ? "success" : "error",
                ok ? "info" : "error",
                ok ? "Heartbeat result" : "Heartbeat execution failed",
                ok ? preview(text, "Heartbeat task finished successfully.") : preview(err.getMessage(), "Heartbeat task failed."),
                Map.of("run_id", runId)
        );
    }

    private String sourceId(CronManager.CronJob job) {
        return value(job.getSourceId(), job.getId());
    }

    private String name(CronManager.CronJob job) {
        return value(job.getName(), sourceId(job));
    }

    private String preview(String value, String fallback) {
        String text = value(value, fallback).trim();
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    private String cleanText(ChannelOutboundMessage out) {
        if (out == null) return "";
        Object visible = out.getMeta().get("visible_text");
        String text = visible != null ? String.valueOf(visible) : "";
        return text.isBlank() ? out.getText() : text;
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 便捷方法: 直接注入消息到默认 Agent.
     */
    public void injectMessage(String message) {
        injectMessage("default", message, "adhoc-" + System.currentTimeMillis());
    }

    private ChannelInboundMessage cronInbound(String agentId, String message, String taskId, Map<String, Object> dispatch) {
        Map<String, Object> target = map(dispatch != null ? dispatch.get("target") : null);
        Map<String, Object> meta = new LinkedHashMap<>(map(dispatch != null ? dispatch.get("meta") : null));
        String channel = value(stringValue(dispatch != null ? dispatch.get("channel") : null), "console");
        String userId = value(stringValue(target.get("user_id")), "default");
        String sessionId = value(stringValue(target.get("session_id")), "cron-" + taskId);
        meta.put("source", "cron");
        meta.put("task_id", taskId);
        meta.put("channel", channel);
        meta.putAll(target);

        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(channel);
        inbound.setUserId(userId);
        inbound.setSessionId(sessionId);
        inbound.setContent(message);
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress("cron", replyId(userId, meta), meta));
        return inbound;
    }

    private String replyId(String userId, Map<String, Object> meta) {
        for (String key : List.of("chat_id", "channel", "channel_id", "room_id", "group_id", "user_id", "reply_url")) {
            String value = stringValue(meta.get(key));
            if (!value.isBlank()) return value;
        }
        return userId;
    }

    private Map<String, Object> map(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

}
