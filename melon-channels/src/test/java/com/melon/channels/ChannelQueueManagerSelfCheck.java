package com.melon.channels;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChannelQueueManagerSelfCheck {

    private ChannelQueueManagerSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        try (ChannelQueueManager queue = new ChannelQueueManager()) {
            ChannelInboundMessage first = message("s1", "first");
            ChannelInboundMessage second = message("s1", "second");
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondStarted = new CountDownLatch(1);
            AtomicInteger call = new AtomicInteger();
            AtomicBoolean overlap = new AtomicBoolean(false);
            AtomicInteger active = new AtomicInteger();

            ChannelProcessor serialProcessor = inbound -> CompletableFuture.supplyAsync(() -> {
                int activeNow = active.incrementAndGet();
                if (activeNow > 1) overlap.set(true);
                int index = call.incrementAndGet();
                if (index == 1) {
                    firstStarted.countDown();
                    await(releaseFirst);
                } else {
                    secondStarted.countDown();
                }
                active.decrementAndGet();
                return outbound(inbound);
            });

            CompletableFuture<ChannelOutboundMessage> out1 = queue.enqueue(first, 10, serialProcessor);
            CompletableFuture<ChannelOutboundMessage> out2 = queue.enqueue(second, 10, serialProcessor);
            if (!firstStarted.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("first task did not start");
            }
            if (secondStarted.await(150, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("same session task started before previous task completed");
            }
            releaseFirst.countDown();
            out1.get(2, TimeUnit.SECONDS);
            out2.get(2, TimeUnit.SECONDS);
            if (overlap.get()) {
                throw new AssertionError("same session tasks overlapped");
            }

            CountDownLatch bothStarted = new CountDownLatch(2);
            CountDownLatch releaseBoth = new CountDownLatch(1);
            AtomicInteger concurrent = new AtomicInteger();
            ChannelProcessor parallelProcessor = inbound -> CompletableFuture.supplyAsync(() -> {
                concurrent.incrementAndGet();
                bothStarted.countDown();
                await(releaseBoth);
                concurrent.decrementAndGet();
                return outbound(inbound);
            });
            CompletableFuture<ChannelOutboundMessage> p1 = queue.enqueue(message("p1", "one"), 10, parallelProcessor);
            CompletableFuture<ChannelOutboundMessage> p2 = queue.enqueue(message("p2", "two"), 10, parallelProcessor);
            if (!bothStarted.await(2, TimeUnit.SECONDS) || concurrent.get() < 2) {
                throw new AssertionError("different sessions should run concurrently");
            }
            releaseBoth.countDown();
            p1.get(2, TimeUnit.SECONDS);
            p2.get(2, TimeUnit.SECONDS);
        }
    }

    private static ChannelInboundMessage message(String sessionId, String content) {
        ChannelInboundMessage message = new ChannelInboundMessage();
        message.setAgentId("default");
        message.setChannel("console");
        message.setUserId("u1");
        message.setSessionId(sessionId);
        message.setContent(content);
        return message;
    }

    private static ChannelOutboundMessage outbound(ChannelInboundMessage inbound) {
        ChannelOutboundMessage out = new ChannelOutboundMessage();
        out.setAgentId(inbound.getAgentId());
        out.setChannel(inbound.getChannel());
        out.setUserId(inbound.getUserId());
        out.setSessionId(inbound.getSessionId());
        out.setText(inbound.getContent());
        out.setMeta(Map.of());
        return out;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
