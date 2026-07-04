package com.melon.tools.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background inter-agent task submission. Mirrors Python submit_to_agent.
 */
public class SubmitToAgentTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(SubmitToAgentTool.class);
    private static final int MAX_POOL_SIZE = 4;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

    private static final ExecutorService TASK_EXECUTOR = Executors.newFixedThreadPool(MAX_POOL_SIZE, r -> {
        Thread t = new Thread(r, "agent-task-" + THREAD_COUNTER.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private static final ScheduledExecutorService TIMEOUT_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-task-timeout");
        t.setDaemon(true);
        return t;
    });
    private static final ConcurrentHashMap<String, TaskEntry> TASK_REGISTRY = new ConcurrentHashMap<>();

    public SubmitToAgentTool() {
        super(ToolBase.builder()
            .name("submit_to_agent")
            .description("Submit a background message to another configured agent. Returns a task ID for later result retrieval.")
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
                      "description": "Task text to send to the target agent"
                    },
                    "session_id": {
                      "type": "string",
                      "description": "Existing inter-agent session ID to continue"
                    },
                    "task_timeout": {
                      "type": "number",
                      "description": "Optional task execution timeout in seconds"
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
        Double taskTimeout = AgentChatBridge.doubleValue(input.get("task_timeout"));

        if (toAgent == null) {
            return Mono.just(ToolResultBlock.error("ERROR: 'to_agent' is required for submission"));
        }
        if (text == null || text.isBlank()) {
            return Mono.just(ToolResultBlock.error("ERROR: 'text' is required for submission"));
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
                300
        );
        TaskEntry entry = submit(request, taskTimeout);
        return Mono.just(ToolResultBlock.text(formatBackgroundSubmissionText(entry)));
    }

    public static TaskEntry submit(AgentChatBridge.AgentRequest request, Double taskTimeoutSeconds) {
        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        TaskEntry entry = new TaskEntry(taskId, request.toAgent(), request.text(), request.sessionId());
        TASK_REGISTRY.put(taskId, entry);

        Future<?> future = TASK_EXECUTOR.submit(() -> {
            entry.markRunning();
            try {
                String result = AgentChatBridge.execute(request);
                entry.markCompleted(result);
                log.info("Agent task completed: task={}, agent={}, session={}", taskId, request.toAgent(), request.sessionId());
            } catch (Exception e) {
                entry.markFailed(e.getMessage());
                log.error("Agent task failed: task={}, agent={}, session={}", taskId, request.toAgent(), request.sessionId(), e);
            }
        });
        entry.setFuture(future);

        if (taskTimeoutSeconds != null && taskTimeoutSeconds > 0) {
            long timeoutMillis = Math.max(1L, Math.round(taskTimeoutSeconds * 1000));
            TIMEOUT_EXECUTOR.schedule(() -> {
                if (!future.isDone()) {
                    future.cancel(true);
                    entry.markFailed("Task cancelled after timeout");
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
        }

        return entry;
    }

    public static TaskEntry getTask(String taskId) {
        return TASK_REGISTRY.get(taskId);
    }

    public static Map<String, Object> statusPayload(String taskId) {
        TaskEntry entry = getTask(taskId);
        if (entry == null) return null;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", entry.getLifecycleStatus());
        response.put("started_at", entry.getStartedAt() > 0 ? entry.getStartedAt() / 1000.0 : entry.getCreatedAt() / 1000.0);
        if ("finished".equals(entry.getLifecycleStatus())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", entry.getResultStatus());
            result.put("session_id", entry.getSessionId());
            if ("completed".equals(entry.getResultStatus())) {
                result.put("output", java.util.List.of(Map.of(
                        "content", java.util.List.of(Map.of("type", "text", "text", entry.getResult() != null ? entry.getResult() : ""))
                )));
            } else {
                result.put("error", Map.of("message", entry.getError() != null ? entry.getError() : "Unknown error"));
            }
            response.put("result", result);
        }
        return response;
    }

    public static String formatBackgroundSubmissionText(TaskEntry entry) {
        return String.join("\n",
                "[TASK_ID: " + entry.getTaskId() + "]",
                "[SESSION: " + entry.getSessionId() + "]",
                "",
                "Task submitted successfully.",
                "Check status with: check_agent_task(task_id='" + entry.getTaskId() + "')"
        );
    }

    public static class TaskEntry {
        private final String taskId;
        private final String agentId;
        private final String text;
        private final String sessionId;
        private final long createdAt;
        private volatile String lifecycleStatus = "submitted";
        private volatile String resultStatus;
        private volatile String result;
        private volatile String error;
        private volatile long startedAt;
        private volatile long finishedAt;
        private volatile Future<?> future;

        public TaskEntry(String taskId, String agentId, String text, String sessionId) {
            this.taskId = taskId;
            this.agentId = agentId;
            this.text = text;
            this.sessionId = sessionId;
            this.createdAt = System.currentTimeMillis();
        }

        void setFuture(Future<?> future) { this.future = future; }
        void markRunning() {
            this.lifecycleStatus = "running";
            this.startedAt = System.currentTimeMillis();
        }
        void markCompleted(String result) {
            this.result = result;
            this.resultStatus = "completed";
            this.lifecycleStatus = "finished";
            this.finishedAt = System.currentTimeMillis();
        }
        void markFailed(String error) {
            this.error = error;
            this.resultStatus = "failed";
            this.lifecycleStatus = "finished";
            this.finishedAt = System.currentTimeMillis();
        }

        public String getTaskId() { return taskId; }
        public String getAgentId() { return agentId; }
        public String getText() { return text; }
        public String getSessionId() { return sessionId; }
        public long getCreatedAt() { return createdAt; }
        public String getLifecycleStatus() { return lifecycleStatus; }
        public String getResultStatus() { return resultStatus; }
        public String getResult() { return result; }
        public String getError() { return error; }
        public long getStartedAt() { return startedAt; }
        public long getFinishedAt() { return finishedAt; }
        public Future<?> getFuture() { return future; }
    }
}
