/**
 * @author melon
 */
package com.melon.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 自动续推中间件. 对应 Python _auto_continue_if_text_only().
 * 纯文本回复但任务可能未完成时, 注入续推提示, 最多 2 次额外推理.
 */
public class AutoContinueMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AutoContinueMiddleware.class);
    private static final int MAX_EXTRA_REASONS = 2;
    private static final String CONTINUE_PROMPT =
        "[System] You gave a text-only response without using any tools. " +
        "If the user's task is complete, that's fine. " +
        "If not, please continue working on it using the appropriate tools.";

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {

        String counterKey = "auto_continue_count";
        Integer count = ctx.get(counterKey, Integer.class);
        int currentCount = count != null ? count : 0;

        return next.apply(input)
                .collectList()
                .flatMapMany(events -> {
                    boolean hasToolUse = events.stream().anyMatch(e ->
                            e.getType() == AgentEventType.TOOL_CALL_START);
                    if (hasToolUse) {
                        ctx.put(counterKey, 0);
                        return Flux.fromIterable(events);
                    }

                    boolean hasReplyEnd = events.stream().anyMatch(e ->
                            e.getType() == AgentEventType.AGENT_END);
                    if (!hasReplyEnd) {
                        return Flux.fromIterable(events);
                    }

                    if (currentCount >= MAX_EXTRA_REASONS) {
                        log.debug("Auto-continue limit reached ({})", currentCount);
                        ctx.put(counterKey, 0);
                        return Flux.fromIterable(events);
                    }

                    log.debug("Text-only response, auto-continue {}/{}", currentCount + 1, MAX_EXTRA_REASONS);
                    ctx.put(counterKey, currentCount + 1);

                    List<Msg> continueMessages = new ArrayList<>(input.messages());
                    continueMessages.add(Msg.builderForRole(MsgRole.USER)
                            .textContent(CONTINUE_PROMPT)
                            .build());
                    ReasoningInput continueInput = new ReasoningInput(
                            continueMessages, input.tools(), input.options());

                    return next.apply(continueInput)
                            .doOnNext(e -> {
                                if (e.getType() == AgentEventType.AGENT_END) {
                                    log.debug("Auto-continue {} completed", currentCount + 1);
                                }
                            });
                });
    }
}
