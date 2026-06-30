/**
 * @author melon
 */
package com.melon.tools.batch;

import reactor.core.publisher.Mono;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Toolkit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes multiple tool calls in a single batch.
 * Corresponds to Python run_tool_batch tool.
 * Always enabled in the toolkit.
 */
public class RunToolBatchTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(RunToolBatchTool.class);
    private static final int MAX_BATCH_SIZE = 10;

    private final Toolkit toolkit;

    public RunToolBatchTool(Toolkit toolkit) {
        super(ToolBase.builder()
            .name("run_tool_batch")
            .description("Execute multiple tool calls in a single batch. Useful for parallel operations like reading multiple files or running multiple searches.")
            .inputSchema(parseSchema("""
                {
                  "type": "object",
                  "properties": {
                    "calls": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "tool_name": {
                            "type": "string",
                            "description": "Name of the tool to call"
                          },
                          "arguments": {
                            "type": "object",
                            "description": "Arguments for the tool call"
                          }
                        },
                        "required": ["tool_name", "arguments"]
                      },
                      "description": "List of tool calls to execute",
                      "maxItems": 10
                    }
                  },
                  "required": ["calls"]
                }"""))
            .readOnly(false)
            .concurrencySafe(false));
        this.toolkit = toolkit;
    }

    private static Map<String, Object> parseSchema(String json) {
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        List<?> calls = (List<?>) param.getInput().get("calls");
        if (calls == null || calls.isEmpty()) {
            return Mono.just(ToolResultBlock.error("calls list is empty"));
        }
        if (calls.size() > MAX_BATCH_SIZE) {
            return Mono.just(ToolResultBlock.error("Too many calls in batch: " + calls.size() + ". Max: " + MAX_BATCH_SIZE));
        }

        List<String> results = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            Object callObj = calls.get(i);
            if (!(callObj instanceof Map<?, ?> callMap)) {
                results.add("[" + i + "] Invalid call format");
                continue;
            }

            String toolName = (String) callMap.get("tool_name");
            Object arguments = callMap.get("arguments");

            if (toolName == null || toolName.isBlank()) {
                results.add("[" + i + "] Missing tool_name");
                continue;
            }

            // Prevent recursive batch calls
            if ("run_tool_batch".equals(toolName)) {
                results.add("[" + i + "] Cannot call run_tool_batch recursively");
                continue;
            }

            try {
                // Get the tool from the toolkit
                AgentTool tool = toolkit.getTool(toolName);
                if (tool == null) {
                    results.add("[" + i + "] Tool not found: " + toolName);
                    continue;
                }

                // Build ToolCallParam from arguments map
                Map<String, Object> argMap = new HashMap<>();
                if (arguments instanceof Map<?, ?> rawMap) {
                    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                        if (entry.getKey() instanceof String key) {
                            argMap.put(key, entry.getValue());
                        }
                    }
                }

                ToolCallParam toolParam = ToolCallParam.builder().input(argMap).build();

                // Execute the tool
                ToolResultBlock result = tool.callAsync(toolParam).block();
                String resultStr = result != null ? result.toString() : "(no result)";
                results.add("[" + i + "] " + toolName + ": " + resultStr);
            } catch (Exception e) {
                log.error("Batch call [{}] failed: {}", i, toolName, e);
                results.add("[" + i + "] " + toolName + " ERROR: " + e.getMessage());
            }
        }

        return Mono.just(ToolResultBlock.text(String.join("\n", results)));
    }
}
