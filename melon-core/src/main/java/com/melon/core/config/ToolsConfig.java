package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
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
        Map<String, BuiltinToolConfig> m = new LinkedHashMap<>();
        put(m, "execute_shell_command", true, "Execute shell commands", "💻");
        put(m, "read_file", true, "Read file contents", "📄");
        put(m, "write_file", true, "Write content to file", "✍️");
        put(m, "edit_file", true, "Edit file using find-and-replace", "🖊️");
        put(m, "grep_search", true, "Search file contents by pattern", "🔍");
        put(m, "glob_search", true, "Find files matching a glob pattern", "📁");
        put(m, "browser_use", true, "Browser automation and web interaction", "🌐");
        put(m, "desktop_screenshot", true, "Capture desktop screenshots", "📸");
        put(m, "view_image", true, "Load an image into LLM context for visual analysis", false, "🖼️");
        put(m, "view_video", true, "Load a video into LLM context for visual analysis", false, "🎥");
        put(m, "send_file_to_user", true, "Send files to user", "📤");
        put(m, "get_current_time", true, "Get current date and time", "🕐");
        put(m, "set_user_timezone", true, "Set user timezone", "🌍");
        put(m, "get_token_usage", true, "Get llm token usage", "📊");
        put(m, "create_cron_job", true, "Create a scheduled task in the current workspace", "⏰");
        put(m, "delegate_external_agent", false, "Delegate work to an external ACP agent runner", "📡");
        put(m, "list_agents", true, "List configured agents from the local API", "🤖");
        put(m, "chat_with_agent", true, "Send a message to another configured agent and wait for the response", "💬");
        put(m, "submit_to_agent", true, "Submit a background task to another configured agent", "📨");
        put(m, "check_agent_task", true, "Check the status of a background agent task", "⏳");
        put(m, "spawn_subagent", true, "Spawn an ephemeral sub-task within the current workspace", "🔀");
        put(m, "recall_history", true, "Search and read durable conversation history", "🧠");
        return m;
    }

    private static void put(Map<String, BuiltinToolConfig> tools, String name, boolean enabled,
                            String description, String icon) {
        put(tools, name, enabled, description, true, icon);
    }

    private static void put(Map<String, BuiltinToolConfig> tools, String name, boolean enabled,
                            String description, boolean displayToUser, String icon) {
        BuiltinToolConfig tool = new BuiltinToolConfig(name, enabled, description);
        tool.setDisplayToUser(displayToUser);
        tool.setIcon(icon);
        tools.put(name, tool);
    }
}
