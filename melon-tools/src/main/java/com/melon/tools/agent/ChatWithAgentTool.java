/**
 * @author melon
 */
package com.melon.tools.agent;

import reactor.core.publisher.Mono;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Sends a message to another agent and optionally waits for a reply.
 * Corresponds to Python chat_with_agent tool.
 */
public class ChatWithAgentTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(ChatWithAgentTool.class);

    private static final long DEFAULT_REPLY_TIMEOUT_MS = 60_000; // 60 seconds

    /** Pending replies: messageKey -> CompletableFuture for the reply */
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingReplies = new ConcurrentHashMap<>();

    /** Functional interface for sending messages to agents (wired to AgentRunner by app layer) */
    @FunctionalInterface
    public interface AgentMessageSender {
        /**
         * Sends a message to the target agent and returns the reply (synchronous).
         *
         * @param agentId  the target agent ID
         * @param message  the message content
         * @return the reply from the target agent, or null if no reply
         * @throws Exception if sending fails
         */
        String send(String agentId, String message) throws Exception;
    }

    private static AgentMessageSender messageSender;

    /**
     * Sets the message sender (wired by the app layer to AgentRunner).
     */
    public static void setMessageSender(AgentMessageSender sender) {
        messageSender = sender;
        log.info("Agent message sender wired");
    }

    public ChatWithAgentTool() {
        super(ToolBase.builder()
            .name("chat_with_agent")
            .description("Send a message to another agent. If wait_for_reply is true, blocks until the target agent responds.")
            .inputSchema(parseSchema("""
                {
                  "type": "object",
                  "properties": {
                    "agent_id": {
                      "type": "string",
                      "description": "Target agent ID"
                    },
                    "message": {
                      "type": "string",
                      "description": "Message to send"
                    },
                    "wait_for_reply": {
                      "type": "boolean",
                      "description": "Whether to wait for a reply",
                      "default": true
                    }
                  },
                  "required": ["agent_id", "message"]
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
        String agentId = (String) param.getInput().get("agent_id");
        String message = (String) param.getInput().get("message");
        boolean waitForReply = ((Boolean) param.getInput().getOrDefault("wait_for_reply", true));

        if (agentId == null || agentId.isBlank()) {
            return Mono.just(ToolResultBlock.error("agent_id is required"));
        }
        if (message == null || message.isBlank()) {
            return Mono.just(ToolResultBlock.error("message is required"));
        }

        log.info("Chat with agent {}: {} (wait={})", agentId, message, waitForReply);

        // Generate a unique message ID for tracking
        String messageId = "msg-" + UUID.randomUUID().toString().substring(0, 8);

        if (messageSender != null) {
            // Use the wired message sender (AgentRunner) for real inter-agent communication
            if (waitForReply) {
                try {
                    String reply = messageSender.send(agentId, message);
                    if (reply == null || reply.isBlank()) {
                        return Mono.just(ToolResultBlock.text("Message sent to agent '" + agentId + "'. No reply received."));
                    }
                    return Mono.just(ToolResultBlock.text("Reply from agent '" + agentId + "':\n" + reply));
                } catch (Exception e) {
                    log.error("Failed to send message to agent {}", agentId, e);
                    return Mono.just(ToolResultBlock.error("Failed to send message to agent '" + agentId + "': " + e.getMessage()));
                }
            } else {
                // Fire-and-forget: send asynchronously
                CompletableFuture.runAsync(() -> {
                    try {
                        messageSender.send(agentId, message);
                    } catch (Exception e) {
                        log.error("Async message to agent {} failed", agentId, e);
                    }
                });
                return Mono.just(ToolResultBlock.text("Message sent to agent '" + agentId + "' (async, no reply expected)."));
            }
        } else {
            // Fallback: no message sender wired
            log.warn("No message sender wired, message to agent {} will be queued locally", agentId);

            if (waitForReply) {
                // Create a future for the reply and wait with timeout
                CompletableFuture<String> future = new CompletableFuture<>();
                pendingReplies.put(messageId, future);

                try {
                    String reply = future.get(DEFAULT_REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    pendingReplies.remove(messageId);
                    return Mono.just(ToolResultBlock.text("Reply from agent '" + agentId + "':\n" + reply));
                } catch (TimeoutException e) {
                    pendingReplies.remove(messageId);
                    return Mono.just(ToolResultBlock.text("Message sent to agent '" + agentId + "'. Reply timed out after "
                            + (DEFAULT_REPLY_TIMEOUT_MS / 1000) + "s."));
                } catch (Exception e) {
                    pendingReplies.remove(messageId);
                    return Mono.just(ToolResultBlock.error("Failed while waiting for reply: " + e.getMessage()));
                }
            } else {
                return Mono.just(ToolResultBlock.text("Message queued for agent '" + agentId + "' (message ID: " + messageId
                        + "). No message sender wired - message will not be delivered."));
            }
        }
    }

    /**
     * Delivers a reply to a pending message (called by the message bus when a reply arrives).
     *
     * @param messageId the message ID returned during send
     * @param reply     the reply content
     */
    public void deliverReply(String messageId, String reply) {
        CompletableFuture<String> future = pendingReplies.get(messageId);
        if (future != null) {
            future.complete(reply);
            log.info("Reply delivered for message {}", messageId);
        }
    }
}
