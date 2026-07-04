package com.melon.channels;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface ChannelInboundDispatcher {

    CompletableFuture<ChannelOutboundMessage> dispatch(ChannelInboundMessage message, int priority);
}
