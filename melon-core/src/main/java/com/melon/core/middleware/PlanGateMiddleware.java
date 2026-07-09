package com.melon.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.function.Function;

/**
 * Deprecated legacy Plan gate for the old Python-style plan tools.
 * Current Java runtime uses AgentScope Harness plan_enter/plan_write/plan_exit.
 */
@Deprecated(forRemoval = false)
public class PlanGateMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(PlanGateMiddleware.class);

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {

        Boolean awaiting = ctx.get("plan_awaiting_confirm", Boolean.class);
        if (Boolean.TRUE.equals(awaiting)) {
            for (var toolCall : input.toolCalls()) {
                if (!isPlanManagementTool(toolCall.getName())) {
                    log.warn("Blocked non-plan tool '{}' while plan awaiting", toolCall.getName());
                    return Flux.just(createBlockedEvent(toolCall.getName(), toolCall.getId()));
                }
            }
        }

        for (var toolCall : input.toolCalls()) {
            if (isPlanManagementTool(toolCall.getName())) {
                ctx.put("plan_awaiting_confirm", true);
                log.debug("Plan management tool called, setting awaiting flag");
            }
            if (isPlanConfirmationTool(toolCall.getName())) {
                ctx.put("plan_awaiting_confirm", false);
                log.debug("Plan confirmed, clearing awaiting flag");
            }
        }

        return next.apply(input);
    }

    private AgentEvent createBlockedEvent(String toolName, String toolCallId) {
        return new CustomEvent("tool_blocked", Map.of(
                "toolName", toolName,
                "toolCallId", toolCallId,
                "reason", "Tool execution blocked: A plan is awaiting user confirmation. " +
                         "Wait for the user to confirm or reject the plan.",
                "isError", true
        ));
    }

    private boolean isPlanManagementTool(String name) {
        return "create_plan".equals(name) || "revise_current_plan".equals(name)
                || "update_plan_task".equals(name);
    }

    private boolean isPlanConfirmationTool(String name) {
        return "confirm_plan".equals(name) || "reject_plan".equals(name)
                || "approve_plan".equals(name);
    }
}
