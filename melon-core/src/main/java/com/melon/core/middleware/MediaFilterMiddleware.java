package com.melon.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.VideoBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 媒体过滤中间件. 对应 Python _reasoning 中的主动/被动媒体过滤.
 * 模型不支持多模态时剥离 ImageBlock/VideoBlock.
 */
public class MediaFilterMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(MediaFilterMiddleware.class);
    private final Set<String> mediaRejectedSlots = ConcurrentHashMap.newKeySet();

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {

        String slotKey = slotKey(agent, ctx);
        final ReasoningInput effectiveInput = mediaRejectedSlots.contains(slotKey) ? stripMediaBlocks(input) : input;

        Flux<AgentEvent> result = next.apply(effectiveInput);

        return result.onErrorResume(e -> {
            if (isMediaError(e)) {
                log.warn("Media error detected for {}, stripping media blocks and retrying", slotKey);
                mediaRejectedSlots.add(slotKey);
                return next.apply(stripMediaBlocks(effectiveInput));
            }
            return Flux.error(e);
        });
    }

    private String slotKey(Agent agent, RuntimeContext ctx) {
        String agentName = agent != null && agent.getName() != null ? agent.getName() : "agent";
        String userId = ctx != null && ctx.getUserId() != null && !ctx.getUserId().isBlank()
                ? ctx.getUserId()
                : "__anon__";
        String sessionId = ctx != null && ctx.getSessionId() != null && !ctx.getSessionId().isBlank()
                ? ctx.getSessionId()
                : "default";
        return agentName + "/" + userId + "/" + sessionId;
    }

    /**
     * 从输入消息中移除 ImageBlock 和 VideoBlock.
     */
    private ReasoningInput stripMediaBlocks(ReasoningInput input) {
        List<Msg> messages = input.messages();
        java.util.concurrent.atomic.AtomicBoolean modified = new java.util.concurrent.atomic.AtomicBoolean(false);

        List<Msg> filtered = messages.stream()
                .map(msg -> {
                    List<ContentBlock> blocks = msg.getContent();
                    List<ContentBlock> filteredBlocks = blocks.stream()
                            .filter(block -> {
                                if (block instanceof ImageBlock || block instanceof VideoBlock) {
                                    modified.set(true);
                                    return false;
                                }
                                return true;
                            })
                            .collect(Collectors.toList());

                    if (filteredBlocks.size() == blocks.size()) return msg;
                    if (filteredBlocks.isEmpty()) {
                        return Msg.builderForRole(msg.getRole())
                                .content(List.of(TextBlock.builder().text("[media content removed]").build()))
                                .build();
                    }
                    return Msg.builderForRole(msg.getRole()).content(filteredBlocks).build();
                })
                .collect(Collectors.toList());

        if (!modified.get()) return input;
        log.debug("Stripped media blocks from {} messages", messages.size());
        return new ReasoningInput(filtered, input.tools(), input.options());
    }

    private boolean isMediaError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("image") || lower.contains("media")
                || lower.contains("multimodal") || lower.contains("unsupported_content_type")
                || lower.contains("video") || lower.contains("does not support images")
                || lower.contains("content type not supported");
    }
}
