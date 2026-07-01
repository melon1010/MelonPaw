package com.melon.core.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plan 状态广播器. 对应 Python plan/broadcaster.py.
 * <p>
 * 使用 Reactor {@link Sinks.Many} 多播 Plan 状态变更,
 * 供 SSE 端点订阅推送.
 */
public class PlanBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(PlanBroadcaster.class);

    /** 多播 sink, 允许多个订阅者. */
    private final Sinks.Many<PlanState> sink = Sinks.many().multicast().onBackpressureBuffer();

    /** 当前活跃的 Plan 状态 (按 sessionId 索引). */
    private final Map<String, PlanState> activePlans = new ConcurrentHashMap<>();

    /**
     * 更新 Plan 状态并广播.
     *
     * @param plan 新的 Plan 状态
     */
    public void updatePlan(PlanState plan) {
        if (plan == null || plan.getSessionId() == null) {
            log.warn("Cannot update plan: plan or sessionId is null");
            return;
        }
        activePlans.put(plan.getSessionId(), plan);
        log.info("Plan updated: session={}, status={}, steps={}",
                plan.getSessionId(), plan.getStatus(), plan.getSteps().size());
        sink.tryEmitNext(plan);
    }

    /**
     * 获取指定 session 的当前 Plan 状态.
     *
     * @param sessionId 会话 ID
     * @return Plan 状态, 不存在则返回 null
     */
    public PlanState getPlan(String sessionId) {
        return activePlans.get(sessionId);
    }

    /**
     * 移除指定 session 的 Plan 状态.
     *
     * @param sessionId 会话 ID
     */
    public void removePlan(String sessionId) {
        PlanState removed = activePlans.remove(sessionId);
        if (removed != null) {
            log.info("Plan removed: session={}", sessionId);
        }
    }

    /**
     * 订阅 Plan 状态变更流.
     * <p>
     * 用于 SSE 端点, 客户端连接时调用.
     *
     * @return Plan 状态变更 Flux
     */
    public Flux<PlanState> subscribe() {
        return sink.asFlux();
    }

    /**
     * 订阅指定 session 的 Plan 状态变更.
     *
     * @param sessionId 会话 ID
     * @return 过滤后的 Plan 状态变更 Flux
     */
    public Flux<PlanState> subscribe(String sessionId) {
        return sink.asFlux()
                .filter(plan -> sessionId.equals(plan.getSessionId()));
    }

    /**
     * 广播状态变更 (不存储, 仅推送).
     *
     * @param plan 要广播的 Plan 状态
     */
    public void broadcast(PlanState plan) {
        if (plan != null) {
            sink.tryEmitNext(plan);
            log.debug("Plan broadcasted: session={}, status={}",
                    plan.getSessionId(), plan.getStatus());
        }
    }

    /**
     * 广播完成事件.
     */
    public void broadcastComplete(String sessionId) {
        PlanState plan = activePlans.get(sessionId);
        if (plan != null) {
            plan.setStatus(PlanState.Status.COMPLETED);
            sink.tryEmitNext(plan);
            log.info("Plan completion broadcasted: session={}", sessionId);
        }
    }

    /**
     * 完成广播, 释放资源.
     */
    public void shutdown() {
        sink.tryEmitComplete();
        activePlans.clear();
        log.info("PlanBroadcaster shut down");
    }

    /**
     * 获取所有活跃 Plan 的 session ID 列表.
     */
    public java.util.Set<String> getActiveSessionIds() {
        return java.util.Collections.unmodifiableSet(activePlans.keySet());
    }
}
