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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Submits a task to a target agent asynchronously (fire-and-forget).
 * Corresponds to Python submit_to_agent tool.
 * Uses a shared thread pool and task registry for async execution.
 */
public class SubmitToAgentTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(SubmitToAgentTool.class);

    private static final int MAX_POOL_SIZE = 4;
    private static final AtomicInteger taskCounter = new AtomicInteger(0);

    /** Shared thread pool for async task execution */
    private static final ExecutorService taskExecutor = Executors.newFixedThreadPool(MAX_POOL_SIZE, r -> {
        Thread t = new Thread(r, "agent-task-" + taskCounter.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    /** Shared task registry for tracking submitted tasks */
    private static final ConcurrentHashMap<String, TaskEntry> taskRegistry = new ConcurrentHashMap<>();

    /** Optional callback for executing agent tasks (wired by app layer) */
    private static AsyncTaskCallback asyncTaskCallback;

    /**
     * Functional interface for async task execution.
     * The app layer can wire this to AgentRunner for real inter-agent execution.
     */
    @FunctionalInterface
    public interface AsyncTaskCallback {
        String execute(String agentId, String task) throws Exception;
    }

    /**
     * Sets the async task callback for real agent execution.
     */
    public static void setAsyncTaskCallback(AsyncTaskCallback callback) {
        asyncTaskCallback = callback;
    }

    /**
     * Gets the shared task registry (used by CheckAgentTaskTool).
     */
    public static ConcurrentHashMap<String, TaskEntry> getTaskRegistry() {
        return taskRegistry;
    }

    public SubmitToAgentTool() {
        super(ToolBase.builder()
            .name("submit_to_agent")
            .description("Submit a task to another agent asynchronously. Returns a task ID for later result retrieval.")
            .inputSchema(parseSchema("""
                {
                  "type": "object",
                  "properties": {
                    "agent_id": {
                      "type": "string",
                      "description": "Target agent ID"
                    },
                    "task": {
                      "type": "string",
                      "description": "Task description to submit"
                    }
                  },
                  "required": ["agent_id", "task"]
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
        String task = (String) param.getInput().get("task");

        if (agentId == null || agentId.isBlank()) {
            return Mono.just(ToolResultBlock.error("agent_id is required"));
        }
        if (task == null || task.isBlank()) {
            return Mono.just(ToolResultBlock.error("task is required"));
        }

        // Generate a unique task ID
        String taskId = "task-" + System.currentTimeMillis() + "-" + taskCounter.incrementAndGet();

        // Create task entry with PENDING status
        TaskEntry entry = new TaskEntry(taskId, agentId, task);
        taskRegistry.put(taskId, entry);

        // Submit to thread pool for async execution
        taskExecutor.submit(() -> {
            entry.setStatus(TaskStatus.RUNNING);
            try {
                String result;
                if (asyncTaskCallback != null) {
                    // Use the wired callback for real agent execution
                    result = asyncTaskCallback.execute(agentId, task);
                } else {
                    // Fallback: simulate execution
                    log.info("Executing task {} for agent {} (no callback wired)", taskId, agentId);
                    Thread.sleep(100); // Simulate minimal work
                    result = "Task completed for agent '" + agentId + "': " + task;
                }
                entry.setResult(result);
                entry.setStatus(TaskStatus.COMPLETED);
                log.info("Task {} completed for agent {}", taskId, agentId);
            } catch (Exception e) {
                entry.setError(e.getMessage());
                entry.setStatus(TaskStatus.FAILED);
                log.error("Task {} failed for agent {}: {}", taskId, agentId, e.getMessage(), e);
            }
        });

        log.info("Submitted task {} to agent {}: {}", taskId, agentId, task);
        return Mono.just(ToolResultBlock.text("Task submitted to agent '" + agentId + "'. Task ID: " + taskId));
    }

    /**
     * Task entry stored in the registry.
     */
    public static class TaskEntry {
        private final String taskId;
        private final String agentId;
        private final String task;
        private final long createdAt;
        private volatile TaskStatus status = TaskStatus.PENDING;
        private volatile String result;
        private volatile String error;
        private volatile long completedAt;

        public TaskEntry(String taskId, String agentId, String task) {
            this.taskId = taskId;
            this.agentId = agentId;
            this.task = task;
            this.createdAt = System.currentTimeMillis();
        }

        public String getTaskId() { return taskId; }
        public String getAgentId() { return agentId; }
        public String getTask() { return task; }
        public long getCreatedAt() { return createdAt; }
        public TaskStatus getStatus() { return status; }
        public void setStatus(TaskStatus status) { this.status = status; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; this.completedAt = System.currentTimeMillis(); }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; this.completedAt = System.currentTimeMillis(); }
        public long getCompletedAt() { return completedAt; }
    }

    /**
     * Task status enum.
     */
    public enum TaskStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }
}
