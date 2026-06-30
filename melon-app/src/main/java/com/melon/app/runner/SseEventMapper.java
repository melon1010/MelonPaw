/**
 * @author melon
 */
package com.melon.app.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentEvent → SSE 事件映射.
 * 对应 Python _stream_printing_messages_interruptible 的消息队列分发.
 */
@Component
public class SseEventMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 将 AgentScope AgentEvent 映射为 Spring SSE 事件.
     */
    public ServerSentEvent<String> map(AgentEvent event) {
        return switch (event.getType()) {
            case TEXT_BLOCK_DELTA -> ServerSentEvent.<String>builder()
                    .event("text_delta")
                    .data(((TextBlockDeltaEvent) event).getDelta())
                    .build();
            case TOOL_CALL_START -> ServerSentEvent.<String>builder()
                    .event("tool_call")
                    .data(((ToolCallStartEvent) event).getToolCallName())
                    .build();
            case TOOL_RESULT_END -> ServerSentEvent.<String>builder()
                    .event("tool_result")
                    .data(serializeResult(event))
                    .build();
            case AGENT_END -> ServerSentEvent.<String>builder()
                    .event("reply_end")
                    .data("[DONE]")
                    .build();
            default -> ServerSentEvent.<String>builder()
                    .event(event.getType().name().toLowerCase())
                    .data("")
                    .build();
        };
    }

    /**
     * Serialize tool result event to JSON string.
     * Contains tool_name, tool_call_id, content, is_error fields.
     */
    private String serializeResult(AgentEvent event) {
        ToolResultEndEvent e = (ToolResultEndEvent) event;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool_name", e.getToolCallName());
        result.put("tool_call_id", e.getToolCallId());
        result.put("state", e.getState().name());
        result.put("is_error", e.getState() == ToolResultState.ERROR);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}
