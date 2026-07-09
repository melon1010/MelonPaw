package com.melon.core.middleware;

import reactor.core.publisher.Mono;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Deprecated stub for the old Python-style passive memory injection.
 * Current Java runtime uses AgentScope Harness memory_search/memory_get/session_search tools.
 */
@Deprecated(forRemoval = false)
public class MemoryInjectionMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(MemoryInjectionMiddleware.class);
    private static final String MEMORY_SEARCH_TOOL_NAME = "memory_search";
    private static final int MAX_MEMORY_RESULTS = 5;

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {

        String query = extractLatestUserQuery(input);
        if (query == null || query.isBlank()) {
            return next.apply(input);
        }

        return searchMemory(agent, ctx, query)
                .flatMapMany(results -> {
                    if (results == null || results.isEmpty()) {
                        log.debug("No memory results for query: {}",
                                query.substring(0, Math.min(50, query.length())));
                        return next.apply(input);
                    }
                    List<Msg> injected = buildMemoryInjectionMessages(query, results);
                    AgentInput modifiedInput = injectBeforeUserMessage(input, injected);
                    log.debug("Injected {} memory results", results.size());
                    return next.apply(modifiedInput);
                })
                .switchIfEmpty(next.apply(input));
    }

    private String extractLatestUserQuery(AgentInput input) {
        List<Msg> messages = input.msgs();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == MsgRole.USER) {
                return extractTextFromMsg(messages.get(i));
            }
        }
        return null;
    }

    private String extractTextFromMsg(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb) sb.append(tb.getText());
        }
        return sb.toString();
    }

    private Mono<List<MemoryResult>> searchMemory(
            Agent agent, RuntimeContext ctx, String query) {
        // Agent interface does not expose a memory() method in AgentScope 2.0.0-RC3;
        // long-term memory is handled at the application layer.
        log.debug("Memory search not available on agent interface");
        return Mono.empty();
    }

    private List<Msg> buildMemoryInjectionMessages(String query, List<MemoryResult> results) {
        StringBuilder resultText = new StringBuilder();
        resultText.append("Found ").append(results.size()).append(" relevant memories:\n\n");
        for (int i = 0; i < results.size(); i++) {
            MemoryResult r = results.get(i);
            resultText.append("--- Memory ").append(i + 1).append(" (score: ")
                      .append(String.format("%.2f", r.score())).append(") ---\n");
            resultText.append(r.content()).append("\n\n");
        }

        String toolUseId = "memory_search_" + System.currentTimeMillis();
        return List.of(
            Msg.builderForRole(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder()
                        .id(toolUseId).name(MEMORY_SEARCH_TOOL_NAME)
                        .input(Map.of("query", query)).build())
                .build(),
            Msg.builderForRole(MsgRole.USER)
                .content(ToolResultBlock.builder()
                        .id(toolUseId)
                        .output(TextBlock.builder().text(resultText.toString()).build())
                        .build())
                .build()
        );
    }

    private AgentInput injectBeforeUserMessage(AgentInput input, List<Msg> injected) {
        List<Msg> messages = new ArrayList<>(input.msgs());
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == MsgRole.USER) { lastUserIdx = i; break; }
        }
        if (lastUserIdx < 0) messages.addAll(injected);
        else messages.addAll(lastUserIdx, injected);
        return new AgentInput(messages);
    }

    private record MemoryResult(String content, double score) {}
}
