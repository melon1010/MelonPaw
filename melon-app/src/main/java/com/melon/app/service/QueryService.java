package com.melon.app.service;

import com.melon.app.runner.AgentRunner;
import com.melon.app.runner.SseEventMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Service for agent query operations (streaming and non-streaming).
 * Wraps AgentRunner with session/context management.
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final AgentRunner agentRunner;
    private final SseEventMapper sseEventMapper;

    public QueryService(AgentRunner agentRunner, SseEventMapper sseEventMapper) {
        this.agentRunner = agentRunner;
        this.sseEventMapper = sseEventMapper;
    }

    /**
     * Processes a non-streaming query.
     */
    public Mono<Map<String, Object>> query(String agentId, String message, String sessionId, String userId) {
        log.info("Query to agent {}: {} (session={}, user={})", agentId, message, sessionId, userId);

        return agentRunner.query(agentId, List.of(new UserMessage(message)), userId, sessionId, Map.of())
            .map(msg -> Map.<String, Object>of(
                "agent_id", agentId,
                "session_id", sessionId,
                "response", msg.getTextContent() != null ? msg.getTextContent() : "",
                "role", "assistant"
            ))
            .onErrorResume(e -> {
                log.error("Query failed", e);
                return Mono.just(Map.<String, Object>of(
                    "error", e.getMessage(),
                    "agent_id", agentId
                ));
            });
    }

    /**
     * Processes a streaming query via SSE.
     */
    public Flux<ServerSentEvent<String>> streamQuery(String agentId, String message, String sessionId, String userId) {
        log.info("Stream query to agent {}: {} (session={}, user={})", agentId, message, sessionId, userId);

        return agentRunner.stream(agentId, List.of(new UserMessage(message)), userId, sessionId, Map.of())
            .map(sseEventMapper::map)
            .onErrorResume(e -> {
                log.error("Stream query failed", e);
                return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("Error: " + e.getMessage())
                    .build());
            });
    }
}
