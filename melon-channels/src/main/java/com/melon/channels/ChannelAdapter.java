package com.melon.channels;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface ChannelAdapter {

    String type();

    boolean implemented();

    default boolean supportsQrcode() {
        return false;
    }

    default boolean supportsStreaming() {
        return false;
    }

    default boolean requiresWebhook() {
        return true;
    }

    CompletableFuture<ChannelHealth> start(String agentId, Map<String, Object> config);

    default CompletableFuture<ChannelHealth> start(String agentId,
                                                   Map<String, Object> config,
                                                   ChannelInboundDispatcher dispatcher) {
        return start(agentId, config);
    }

    CompletableFuture<ChannelHealth> stop(String agentId);

    ChannelHealth health(String agentId, Map<String, Object> config);

    ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body, Map<String, String> headers);

    CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config);

    default Map<String, Object> qrcode(String agentId, Map<String, Object> config) {
        return Map.of(
                "qrcode_img", "",
                "poll_token", "",
                "enabled", false,
                "status", "unsupported",
                "detail", "QR-code login is not available for this channel."
        );
    }

    default Map<String, Object> qrcodeStatus(String agentId, Map<String, Object> config, String token) {
        return Map.of("status", "unsupported", "credentials", Map.of());
    }

    default void close() {
    }
}
