package com.melon.app.runner;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import com.melon.core.agent.MultiAgentManager;
import com.melon.app.service.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.melon.core.util.ValueUtils.stringValue;

/**
 * 请求→Agent 调用编排. 对应 Python runner/runner.py 的 query_handler.
 * 区别: 不新建 Agent, 复用单例; AgentScope 自动管理 state load/save.
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final MultiAgentManager multiAgentManager;
    private final ApprovalService approvalService;

    public AgentRunner(MultiAgentManager multiAgentManager, ApprovalService approvalService) {
        this.multiAgentManager = multiAgentManager;
        this.approvalService = approvalService;
    }

    /**
     * 流式查询. 对应 Python _stream_printing_messages_interruptible.
     */
    public Flux<AgentEvent> stream(String agentId, List<Msg> msgs,
                                    String userId, String sessionId,
                                    Map<String, Object> envInfo) {
        log.info("Stream query: agent={}, user={}, session={}", agentId, userId, sessionId);

        return Flux.defer(() -> {
                    HarnessAgent agent = multiAgentManager.getOrCreate(agentId);
                    RuntimeContext ctx = buildContext(userId, sessionId, envInfo);
                    return streamWithApprovals(agent, msgs, ctx, agentId, sessionId);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("Agent stream failed: agent={}, user={}, session={}", agentId, userId, sessionId, e));
    }

    /**
     * 非流式查询.
     */
    public Mono<Msg> query(String agentId, List<Msg> msgs,
                            String userId, String sessionId,
                            Map<String, Object> envInfo) {
        log.info("Query: agent={}, user={}, session={}", agentId, userId, sessionId);

        return Mono.defer(() -> {
                    HarnessAgent agent = multiAgentManager.getOrCreate(agentId);
                    RuntimeContext ctx = buildContext(userId, sessionId, envInfo);
                    return agent.call(msgs, ctx);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private RuntimeContext buildContext(String userId, String sessionId, Map<String, Object> envInfo) {
        String sid = sessionId != null ? sessionId : "default";
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .sessionId(sid);

        builder.put("session_id", sid);
        if (userId != null) {
            builder.put("user_id", userId);
        }

        if (envInfo != null) {
            if (envInfo.get("agent_id") != null) {
                builder.put("agent_id", envInfo.get("agent_id"));
            }
            if (envInfo.get("working_dir") != null) {
                builder.put("working_dir", envInfo.get("working_dir"));
            }
            if (envInfo.get("channel") != null) {
                builder.put("channel", envInfo.get("channel"));
            }
            if (envInfo.get("shell") != null) {
                builder.put("shell", envInfo.get("shell"));
            }
            if (envInfo.get("project_dir") != null) {
                builder.put("project_dir", envInfo.get("project_dir"));
            }
            if (envInfo.get("source") != null) {
                builder.put("source", envInfo.get("source"));
            }
        }

        return builder.build();
    }

    private Flux<AgentEvent> streamWithApprovals(HarnessAgent agent, List<Msg> msgs, RuntimeContext ctx,
                                                 String agentId, String sessionId) {
        String sid = sessionId != null ? sessionId : "default";
        AtomicReference<Mono<List<ConfirmResult>>> pending = new AtomicReference<>();
        return Flux.defer(() -> agent.streamEvents(msgs, ctx))
                .subscribeOn(Schedulers.boundedElastic())
                .<AgentEvent>handle((event, sink) -> {
                    if (event instanceof RequireUserConfirmEvent confirm) {
                        List<Map<String, Object>> requests = confirm.getToolCalls().stream()
                                .map(toolCall -> normalizeApproval(agentId, sid, toolCall))
                                .toList();
                        pending.set(approvalService.openPendingApproval(sid, confirm.getToolCalls(), requests));
                        sink.next(event);
                        return;
                    }
                    if (pending.get() != null && isPermissionStopEvent(event)) {
                        return;
                    }
                    sink.next(event);
                })
                .concatWith(Flux.defer(() -> {
                    Mono<List<ConfirmResult>> wait = pending.get();
                    if (wait == null) return Flux.empty();
                    return wait.publishOn(Schedulers.boundedElastic()).flatMapMany(results -> {
                        Msg confirm = UserMessage.builder()
                                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                                .build();
                        return streamWithApprovals(agent, List.of(confirm), ctx, agentId, sid);
                    });
                }));
    }

    private boolean isPermissionStopEvent(AgentEvent event) {
        AgentEventType type = event.getType();
        return type == AgentEventType.REQUEST_STOP || type == AgentEventType.AGENT_END;
    }

    private Map<String, Object> normalizeApproval(String agentId, String sessionId, ToolUseBlock toolCall) {
        String sid = sessionId != null ? sessionId : "default";
        String aid = stringValue(agentId, "default");
        Map<String, Object> approval = new java.util.LinkedHashMap<>();
        approval.put("request_id", toolCall.getId());
        approval.put("session_id", sid);
        approval.put("root_session_id", sid);
        approval.put("agent_id", aid);
        approval.put("owner_agent_id", aid);
        approval.put("tool_name", toolCall.getName());
        approval.put("tool_display_name", toolCall.getName());
        approval.put("tool_source", "agentscope");
        approval.put("severity", "medium");
        approval.put("findings_count", 1);
        approval.put("findings_summary", "AgentScope requires approval before running this tool.");
        approval.put("tool_params", toolCall.getInput());
        approval.put("created_at", System.currentTimeMillis() / 1000.0);
        approval.put("timeout_seconds", 300);
        return approval;
    }

}
