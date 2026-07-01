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
 * Spawns a subagent for a specific subtask, enabling parallel/delegated work.
 * Corresponds to Python spawn_subagent tool.
 */
public class SpawnSubagentTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(SpawnSubagentTool.class);

    /** Thread pool for async subagent execution */
    private static final ExecutorService subagentExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "subagent-runner");
        t.setDaemon(true);
        return t;
    });

    /** Registry of spawned subagents: subagentId -> SubagentEntry */
    private static final ConcurrentHashMap<String, SubagentEntry> subagentRegistry = new ConcurrentHashMap<>();

    /** Functional interface for spawning subagents (wired by app layer) */
    @FunctionalInterface
    public interface SubagentRunner {
        /**
         * Spawns and runs a subagent with the given configuration.
         *
         * @param agentId the agent type/ID to spawn
         * @param task    the task description
         * @param context additional context
         * @return the subagent's result
         * @throws Exception if spawning or execution fails
         */
        String run(String agentId, String task, String context) throws Exception;
    }

    private static SubagentRunner subagentRunner;

    /**
     * Sets the subagent runner (wired by the app layer to create isolated RuntimeContext and start agent).
     */
    public static void setSubagentRunner(SubagentRunner runner) {
        subagentRunner = runner;
        log.info("Subagent runner wired");
    }

    public SpawnSubagentTool() {
        super(ToolBase.builder()
            .name("spawn_subagent")
            .description("Spawn a subagent for a specific subtask. The subagent runs in its own context with a fresh session.")
            .inputSchema(parseSchema("""
                {
                  "type": "object",
                  "properties": {
                    "agent_id": {
                      "type": "string",
                      "description": "Agent type/ID to spawn (e.g. 'default', 'coder')"
                    },
                    "task": {
                      "type": "string",
                      "description": "Task description for the subagent"
                    },
                    "context": {
                      "type": "string",
                      "description": "Additional context to pass to the subagent"
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
        String context = (String) param.getInput().getOrDefault("context", "");

        if (agentId == null || agentId.isBlank()) {
            return Mono.just(ToolResultBlock.error("agent_id is required"));
        }
        if (task == null || task.isBlank()) {
            return Mono.just(ToolResultBlock.error("task is required"));
        }

        // Generate a unique subagent ID
        String subagentId = agentId + "-sub-" + UUID.randomUUID().toString().substring(0, 8);
        long createdAt = System.currentTimeMillis();

        // Create registry entry
        SubagentEntry entry = new SubagentEntry(subagentId, agentId, task, context, createdAt);
        subagentRegistry.put(subagentId, entry);

        log.info("Spawning subagent {} for task: {} (context={})", subagentId, task, context);

        // Start the subagent asynchronously
        subagentExecutor.submit(() -> {
            entry.setStatus("RUNNING");
            try {
                String result;
                if (subagentRunner != null) {
                    // Use the wired runner for real subagent execution with isolated RuntimeContext
                    result = subagentRunner.run(agentId, task, context);
                } else {
                    // Fallback: simulate execution
                    log.info("Running subagent {} (no runner wired)", subagentId);
                    Thread.sleep(100);
                    result = "Subagent " + subagentId + " completed task: " + task;
                }
                entry.setResult(result);
                entry.setStatus("COMPLETED");
                log.info("Subagent {} completed", subagentId);
            } catch (Exception e) {
                entry.setError(e.getMessage());
                entry.setStatus("FAILED");
                log.error("Subagent {} failed: {}", subagentId, e.getMessage(), e);
            }
        });

        return Mono.just(ToolResultBlock.text("Subagent spawned: " + subagentId
                + "\nAgent type: " + agentId
                + "\nTask: " + task
                + "\nStatus: RUNNING"
                + "\n\nUse check_agent_task with task ID '" + subagentId + "' to check status."));
    }

    /**
     * Gets the subagent registry (for status lookups).
     */
    public static ConcurrentHashMap<String, SubagentEntry> getSubagentRegistry() {
        return subagentRegistry;
    }

    /**
     * Registry entry for a spawned subagent.
     */
    public static class SubagentEntry {
        private final String subagentId;
        private final String agentId;
        private final String task;
        private final String context;
        private final long createdAt;
        private volatile String status = "SPAWNING";
        private volatile String result;
        private volatile String error;
        private volatile long completedAt;

        public SubagentEntry(String subagentId, String agentId, String task, String context, long createdAt) {
            this.subagentId = subagentId;
            this.agentId = agentId;
            this.task = task;
            this.context = context;
            this.createdAt = createdAt;
        }

        public String getSubagentId() { return subagentId; }
        public String getAgentId() { return agentId; }
        public String getTask() { return task; }
        public String getContext() { return context; }
        public long getCreatedAt() { return createdAt; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; this.completedAt = System.currentTimeMillis(); }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; this.completedAt = System.currentTimeMillis(); }
        public long getCompletedAt() { return completedAt; }
    }
}
