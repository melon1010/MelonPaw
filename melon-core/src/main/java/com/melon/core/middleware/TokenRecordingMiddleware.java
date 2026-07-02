package com.melon.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.function.Function;

/**
 * Token 用量记录中间件. 对应 Python TokenRecordingModelWrapper.
 * 在 onModelCall 钩子记录 prompt/completion tokens.
 */
public class TokenRecordingMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(TokenRecordingMiddleware.class);

    private final TokenUsageCallback callback;

    public TokenRecordingMiddleware() { this(null); }

    public TokenRecordingMiddleware(TokenUsageCallback callback) {
        this.callback = callback;
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, RuntimeContext ctx, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {

        long startTime = System.currentTimeMillis();

        return next.apply(input).doOnNext(event -> {
            if (event.getType() == AgentEventType.AGENT_END) {
                long latency = System.currentTimeMillis() - startTime;
                TokenUsage usage = extractTokenUsage(event, input);

                log.debug("Model call: agent={}, model={}, prompt={}, completion={}, total={}, latency={}ms",
                        agent.getName(), input.model(),
                        usage.promptTokens(), usage.completionTokens(),
                        usage.totalTokens(), latency);

                if (callback != null && usage.totalTokens() > 0) {
                    String sessionId = ctx.getSessionId();
                    String modelName = input.model() != null ? input.model().getModelName() : "";
                    callback.record(agent.getName(), sessionId, modelName, usage, latency);
                }
            }
        });
    }

    private TokenUsage extractTokenUsage(AgentEvent event, ModelCallInput input) {
        long promptTokens = 0, completionTokens = 0, totalTokens = 0;

        try {
            Map<String, Object> metadata = event.getMetadata();
            if (metadata != null) {
                Object usageObj = metadata.get("usage");
                if (usageObj instanceof Map<?, ?> map) {
                    promptTokens = getLong(map.get("prompt_tokens"));
                    completionTokens = getLong(map.get("completion_tokens"));
                    totalTokens = getLong(map.get("total_tokens"));
                }
                if (totalTokens == 0) {
                    Long tt = getLongObj(metadata.get("total_tokens"));
                    if (tt != null) totalTokens = tt;
                }
                if (promptTokens == 0) {
                    Long pt = getLongObj(metadata.get("prompt_tokens"));
                    if (pt != null) promptTokens = pt;
                }
                if (completionTokens == 0) {
                    Long ct = getLongObj(metadata.get("completion_tokens"));
                    if (ct != null) completionTokens = ct;
                }
            }
        } catch (Exception e) {
            log.trace("Failed to extract token usage: {}", e.getMessage());
        }

        if (totalTokens == 0) {
            promptTokens = Math.max(1, input.toString().length() / 4);
            totalTokens = promptTokens + completionTokens;
        }

        return new TokenUsage(promptTokens, completionTokens, totalTokens);
    }

    private long getLong(Object val) {
        return val instanceof Number n ? n.longValue() : 0;
    }

    private Long getLongObj(Object val) {
        return val instanceof Number n ? n.longValue() : null;
    }

    public record TokenUsage(long promptTokens, long completionTokens, long totalTokens) {}

    @FunctionalInterface
    public interface TokenUsageCallback {
        void record(String agentId, String sessionId, String modelName, TokenUsage usage, long latencyMs);
    }
}
