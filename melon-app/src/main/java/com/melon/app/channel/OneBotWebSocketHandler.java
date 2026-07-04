package com.melon.app.channel;

import com.melon.channels.ChannelAdapter;
import com.melon.channels.ChannelAdapterRegistry;
import com.melon.channels.ChannelManager;
import com.melon.channels.OneBotChannelAdapter;
import com.melon.core.util.JsonUtils;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class OneBotWebSocketHandler implements WebSocketHandler {

    private final ChannelAdapterRegistry registry;
    private final ChannelManager channelManager;

    public OneBotWebSocketHandler(ChannelAdapterRegistry registry, ChannelManager channelManager) {
        this.registry = registry;
        this.channelManager = channelManager;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String agentId = agentId(session);
        ChannelAdapter adapter = registry.get("onebot");
        if (!(adapter instanceof OneBotChannelAdapter oneBot)) {
            return session.close();
        }
        String connectionId = UUID.randomUUID().toString();
        Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        oneBot.attach(agentId, new OneBotChannelAdapter.OneBotConnection() {
            @Override
            public CompletableFuture<Void> send(String payload) {
                Sinks.EmitResult result = outbound.tryEmitNext(payload);
                return result.isSuccess()
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new IllegalStateException(result.name()));
            }

            @Override
            public String id() {
                return connectionId;
            }
        });

        Mono<Void> receive = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .flatMap(payload -> handlePayload(agentId, payload))
                .then()
                .doFinally(signal -> {
                    oneBot.detach(agentId, connectionId);
                    outbound.tryEmitComplete();
                });
        Mono<Void> send = session.send(outbound.asFlux().map(session::textMessage));
        return send.and(receive);
    }

    private Mono<Void> handlePayload(String agentId, String payload) {
        Map<String, Object> body = JsonUtils.fromJson(payload, Map.class);
        if (body == null) body = new LinkedHashMap<>();
        String postType = String.valueOf(body.getOrDefault("post_type", ""));
        if (!postType.isBlank() && !"message".equals(postType)) {
            return Mono.empty();
        }
        return Mono.fromFuture(channelManager.handleWebhook(agentId, "onebot", body, Map.of())).then();
    }

    private String agentId(WebSocketSession session) {
        String value = session.getHandshakeInfo().getUri().getQuery();
        if (value == null || value.isBlank()) return "default";
        for (String item : value.split("&")) {
            String[] parts = item.split("=", 2);
            if (parts.length == 2 && ("agent_id".equals(parts[0]) || "agentId".equals(parts[0]))) {
                return parts[1].isBlank() ? "default" : parts[1];
            }
        }
        return "default";
    }
}
