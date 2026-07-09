package com.melon.core.config;

public final class ToolsConfigSelfCheck {
    private static final java.util.List<String> PYTHON_DEFAULT_TOOLS = java.util.List.of(
            "execute_shell_command",
            "read_file",
            "write_file",
            "edit_file",
            "grep_search",
            "glob_search",
            "browser_use",
            "desktop_screenshot",
            "view_image",
            "view_video",
            "send_file_to_user",
            "get_current_time",
            "set_user_timezone",
            "get_token_usage",
            "create_cron_job",
            "delegate_external_agent",
            "list_agents",
            "chat_with_agent",
            "submit_to_agent",
            "check_agent_task",
            "spawn_subagent",
            "recall_history",
            "materialize_skill"
    );

    private ToolsConfigSelfCheck() {
    }

    public static void main(String[] args) {
        ConfigManager configManager = new ConfigManager();
        ToolsConfig tools = configManager.getConfig().getAgents().get("default").getTools();

        assertVisible(tools, "execute_shell_command");
        assertVisible(tools, "grep_search");
        assertVisible(tools, "glob_search");
        assertVisible(tools, "materialize_skill");
        assertHidden(tools, "view_image");
        assertHidden(tools, "view_video");
        assertDisabled(tools, "delegate_external_agent");
        assertHiddenOrMissing(tools, "execute");
        assertHiddenOrMissing(tools, "grep_files");
        assertHiddenOrMissing(tools, "glob_files");
        assertPythonDefaultNames();
    }

    private static void assertVisible(ToolsConfig tools, String name) {
        BuiltinToolConfig tool = tools.getBuiltinTools().get(name);
        if (tool == null || !tool.isDisplayToUser()) {
            throw new AssertionError("expected visible Python tool name: " + name);
        }
    }

    private static void assertHiddenOrMissing(ToolsConfig tools, String name) {
        BuiltinToolConfig tool = tools.getBuiltinTools().get(name);
        if (tool != null && tool.isDisplayToUser()) {
            throw new AssertionError("Java internal tool name should not be visible: " + name);
        }
    }

    private static void assertHidden(ToolsConfig tools, String name) {
        BuiltinToolConfig tool = tools.getBuiltinTools().get(name);
        if (tool == null || tool.isDisplayToUser()) {
            throw new AssertionError("expected hidden Python tool name: " + name);
        }
    }

    private static void assertDisabled(ToolsConfig tools, String name) {
        BuiltinToolConfig tool = tools.getBuiltinTools().get(name);
        if (tool == null || tool.isEnabled()) {
            throw new AssertionError("expected disabled Python tool name: " + name);
        }
    }

    private static void assertPythonDefaultNames() {
        java.util.Set<String> actual = new java.util.LinkedHashSet<>(new ToolsConfig().getBuiltinTools().keySet());
        java.util.Set<String> expected = new java.util.LinkedHashSet<>(PYTHON_DEFAULT_TOOLS);
        if (!actual.equals(expected)) {
            throw new AssertionError("tool names differ from Python defaults. expected="
                    + expected + ", actual=" + actual);
        }
    }
}
