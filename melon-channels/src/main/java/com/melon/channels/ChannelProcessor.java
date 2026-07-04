package com.melon.channels;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface ChannelProcessor {
    CompletableFuture<ChannelOutboundMessage> process(ChannelInboundMessage message);
}
