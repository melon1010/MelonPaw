package com.melon.tools.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Foreground inter-agent chat. Mirrors Python chat_with_agent.
 */
public class ChatWithAgentTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(ChatWithAgentTool.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 300;

    public ChatWithAgentTool() {
        super(ToolBase.builder()
            .name("chat_with_agent")
            .description("Send a foreground message to another configured agent and wait for its final reply.")
            .inputSchema(parseSchema("""
                {
                  "type": "object",
                  "properties": {
                    "to_agent": {
                      "type": "string",
                      "description": "Target agent ID returned by list_agents"
                    },
                    "text": {
                      "type": "string",
                      "description": "Message text to send to the target agent"
                    },
                    "session_id": {
                      "type": "string",
                      "description": "Existing inter-agent session ID to continue"
                    },
                    "timeout": {
                      "type": "integer",
                      "description": "Foreground wait timeout in seconds",
                      "default": 300
                    }
                  },
                  "required": ["to_agent", "text"]
                }"""))
            .readOnly(false)
            .concurrencySafe(false));
    }

    private static Map<String, Object> parseSchema(String json) {
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String toAgent = AgentChatBridge.normalizeId(input.get("to_agent"));
        String text = AgentChatBridge.text(input, "text");
        String sessionId = AgentChatBridge.normalizeId(input.get("session_id"));
        long timeout = AgentChatBridge.longValue(input.get("timeout"), DEFAULT_TIMEOUT_SECONDS);

        if (toAgent == null) {
            return Mono.just(ToolResultBlock.error("ERROR: 'to_agent' is required for chat"));
        }
        if (text == null || text.isBlank()) {
            return Mono.just(ToolResultBlock.error("ERROR: 'text' is required for chat"));
        }

        String fromAgent = AgentChatBridge.callingAgentId(param);
        String finalSessionId = sessionId != null ? sessionId : AgentChatBridge.generateSessionId(fromAgent, toAgent);
        String finalText = AgentChatBridge.ensureIdentityPrefix(text, fromAgent);
        String rootSessionId = AgentChatBridge.rootSessionId(param);

        AgentChatBridge.AgentRequest request = new AgentChatBridge.AgentRequest(
                toAgent,
                finalText,
                finalSessionId,
                fromAgent,
                rootSessionId,
                timeout
        );

        return Mono.fromCallable(() -> {
            log.info("chat_with_agent: from={}, to={}, session={}", fromAgent, toAgent, finalSessionId);
            String reply = AgentChatBridge.execute(request);
            return ToolResultBlock.text(AgentChatBridge.formatAgentChatText(reply, finalSessionId));
        }).onErrorResume(e -> Mono.just(ToolResultBlock.error(e.getMessage())));
    }
}
