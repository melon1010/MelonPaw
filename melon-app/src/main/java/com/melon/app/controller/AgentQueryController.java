/**
 * @author melon
 */
package com.melon.app.controller;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import com.melon.app.runner.AgentRunner;
import com.melon.app.runner.SseEventMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Agent 查询控制器. 对应 Python /api/agent/process + /api/agent/stream_query.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentQueryController {

    @Autowired
    private AgentRunner agentRunner;

    @Autowired
    private SseEventMapper sseMapper;

    /**
     * 非流式查询.
     * POST /api/agent/process
     */
    @PostMapping("/process")
    public Mono<String> process(@RequestBody QueryRequest request,
                                 @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
                                 @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {
        List<Msg> msgs = List.of(new UserMessage(request.getText()));
        return agentRunner.query(request.getAgentId(), msgs, userId, sessionId, request.getEnvInfo())
                .map(Msg::getTextContent);
    }

    /**
     * SSE 流式查询.
     * POST /api/agent/stream_query
     */
    @PostMapping(value = "/stream_query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamQuery(@RequestBody QueryRequest request,
                                                      @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
                                                      @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {
        List<Msg> msgs = List.of(new UserMessage(request.getText()));
        return agentRunner.stream(request.getAgentId(), msgs, userId, sessionId, request.getEnvInfo())
                .map(sseMapper::map);
    }

    /**
     * 查询请求体.
     */
    public static class QueryRequest {
        private String agentId = "default";
        private String text;
        private Map<String, Object> envInfo;

        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public Map<String, Object> getEnvInfo() { return envInfo; }
        public void setEnvInfo(Map<String, Object> envInfo) { this.envInfo = envInfo; }
    }
}
