/**
 * @author melon
 */
package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具开关配置. 对应 Python config.py 的 ToolsConfig.
 */
public class ToolsConfig {

    @JsonProperty("builtin_tools")
    private Map<String, BuiltinToolConfig> builtinTools = defaultBuiltinTools();

    @JsonProperty("async_execution_tools")
    private Map<String, Boolean> asyncExecutionTools = Map.of(
        "execute_shell_command", true,
        "delegate_external_agent", true
    );

    public Map<String, BuiltinToolConfig> getBuiltinTools() { return builtinTools; }
    public void setBuiltinTools(Map<String, BuiltinToolConfig> builtinTools) { this.builtinTools = builtinTools; }

    public Map<String, Boolean> getAsyncExecutionTools() { return asyncExecutionTools; }
    public void setAsyncExecutionTools(Map<String, Boolean> asyncExecutionTools) { this.asyncExecutionTools = asyncExecutionTools; }

    /**
     * 默认工具开关. 对应 Python _default_builtin_tools().
     */
    private static Map<String, BuiltinToolConfig> defaultBuiltinTools() {
        Map<String, BuiltinToolConfig> m = new HashMap<>();
        m.put("execute_shell_command", new BuiltinToolConfig("execute_shell_command", true, "Execute shell commands"));
        m.put("read_file", new BuiltinToolConfig("read_file", true, "Read file contents"));
        m.put("write_file", new BuiltinToolConfig("write_file", true, "Write content to file"));
        m.put("edit_file", new BuiltinToolConfig("edit_file", true, "Edit file using find-and-replace"));
        m.put("grep_search", new BuiltinToolConfig("grep_search", true, "Search file contents by pattern"));
        m.put("glob_search", new BuiltinToolConfig("glob_search", true, "Find files matching a glob pattern"));
        m.put("browser_use", new BuiltinToolConfig("browser_use", true, "Browser automation and web interaction"));
        m.put("desktop_screenshot", new BuiltinToolConfig("desktop_screenshot", true, "Capture desktop screenshots"));
        m.put("view_image", new BuiltinToolConfig("view_image", true, "Load an image into LLM context"));
        m.put("view_video", new BuiltinToolConfig("view_video", true, "Load a video into LLM context"));
        m.put("send_file_to_user", new BuiltinToolConfig("send_file_to_user", true, "Send files to user"));
        m.put("get_current_time", new BuiltinToolConfig("get_current_time", true, "Get current date and time"));
        m.put("set_user_timezone", new BuiltinToolConfig("set_user_timezone", true, "Set user timezone"));
        m.put("get_token_usage", new BuiltinToolConfig("get_token_usage", true, "Get llm token usage"));
        m.put("list_agents", new BuiltinToolConfig("list_agents", true, "List configured agents"));
        m.put("chat_with_agent", new BuiltinToolConfig("chat_with_agent", true, "Send a message to another agent"));
        m.put("submit_to_agent", new BuiltinToolConfig("submit_to_agent", true, "Submit a background task to another agent"));
        m.put("check_agent_task", new BuiltinToolConfig("check_agent_task", true, "Check background agent task status"));
        m.put("spawn_subagent", new BuiltinToolConfig("spawn_subagent", true, "Spawn an ephemeral sub-agent"));
        m.put("delegate_external_agent", new BuiltinToolConfig("delegate_external_agent", false, "Delegate to external ACP agent"));
        return m;
    }
}
