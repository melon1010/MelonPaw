/**
 * @author melon
 */
package com.melon.app.runner;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import com.melon.core.agent.MultiAgentManager;
import com.melon.app.service.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 请求→Agent 调用编排. 对应 Python runner/runner.py 的 query_handler.
 * 区别: 不新建 Agent, 复用单例; AgentScope 自动管理 state load/save.
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    @Autowired
    private MultiAgentManager multiAgentManager;

    @Autowired
    private ApprovalService approvalService;

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
                    return agent.streamEvents(msgs, ctx);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(event -> captureApproval(agentId, sessionId, event))
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

    public Mono<Msg> confirm(String agentId, String userId, String sessionId, boolean approved) {
        return Mono.defer(() -> {
                    HarnessAgent agent = multiAgentManager.getOrCreate(agentId);
                    ToolUseBlock toolCall = approvalService.removePendingToolCall(sessionId);
                    if (toolCall == null) {
                        return Mono.empty();
                    }
                    Msg confirm = io.agentscope.core.message.UserMessage.builder()
                            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(new ConfirmResult(approved, toolCall))))
                            .build();
                    RuntimeContext ctx = buildContext(userId, sessionId, Map.of("agent_id", agentId, "source", "console"));
                    return agent.call(List.of(confirm), ctx);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private RuntimeContext buildContext(String userId, String sessionId, Map<String, Object> envInfo) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .sessionId(sessionId != null ? sessionId : "default");

        if (envInfo != null) {
            if (userId != null) {
                builder.put("user_id", userId);
            }
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

    private void captureApproval(String agentId, String sessionId, AgentEvent event) {
        if (event instanceof RequireUserConfirmEvent confirm) {
            String sid = sessionId != null ? sessionId : "default";
            for (ToolUseBlock toolCall : confirm.getToolCalls()) {
                approvalService.setPendingApproval(sid, toolCall, normalizeApproval(agentId, sid, toolCall));
            }
        }
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

    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

}
