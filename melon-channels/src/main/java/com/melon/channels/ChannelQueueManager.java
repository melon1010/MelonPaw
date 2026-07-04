package com.melon.channels;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChannelQueueManager implements AutoCloseable {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<QueueKey, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    public CompletableFuture<ChannelOutboundMessage> enqueue(ChannelInboundMessage message,
                                                             int priority,
                                                             ChannelProcessor processor) {
        QueueKey key = new QueueKey(message.getAgentId(), message.getChannel(), message.getSessionId(), priority);
        CompletableFuture<ChannelOutboundMessage> result = new CompletableFuture<>();
        tails.compute(key, (ignored, tail) -> {
            CompletableFuture<Void> base = tail != null ? tail.exceptionally(e -> null) : CompletableFuture.completedFuture(null);
            return base.thenRunAsync(() -> {
                try {
                    result.complete(processor.process(message).get());
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            }, executor);
        });
        return result;
    }

    public void clear(String agentId, String channel, String sessionId) {
        tails.keySet().removeIf(key -> key.agentId.equals(agentId)
                && key.channel.equals(channel)
                && key.sessionId.equals(sessionId));
    }

    @Override
    public void close() {
        executor.shutdownNow();
        tails.clear();
    }

    private record QueueKey(String agentId, String channel, String sessionId, int priority) {
        private QueueKey {
            agentId = value(agentId, "default");
            channel = value(channel, "console");
            sessionId = value(sessionId, "default");
            Objects.requireNonNull(agentId);
            Objects.requireNonNull(channel);
            Objects.requireNonNull(sessionId);
        }

        private static String value(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
