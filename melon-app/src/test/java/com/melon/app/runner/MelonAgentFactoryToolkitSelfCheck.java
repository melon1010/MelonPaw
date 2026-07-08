package com.melon.app.runner;

import com.melon.core.agent.MelonAgentFactory;
import com.melon.core.config.AgentConfig;
import io.agentscope.core.tool.Toolkit;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MelonAgentFactoryToolkitSelfCheck {

    private MelonAgentFactoryToolkitSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        MelonAgentFactory factory = new MelonAgentFactory();
        Method buildToolkit = MelonAgentFactory.class.getDeclaredMethod("buildToolkit", AgentConfig.class, Path.class);
        buildToolkit.setAccessible(true);

        Toolkit toolkit = (Toolkit) buildToolkit.invoke(factory, new AgentConfig(), Files.createTempDirectory("melon-toolkit-check"));

        assertPresent(toolkit, "execute_shell_command");
        assertPresent(toolkit, "grep_search");
        assertPresent(toolkit, "glob_search");
        assertPresent(toolkit, "browser_use");
        assertPresent(toolkit, "list_agents");
        assertPresent(toolkit, "spawn_subagent");
        assertAbsent(toolkit, "execute");
        assertAbsent(toolkit, "grep_files");
        assertAbsent(toolkit, "glob_files");
        assertAbsent(toolkit, "append_file");
        assertAbsent(toolkit, "materialize_skill");
    }

    private static void assertPresent(Toolkit toolkit, String name) {
        if (!toolkit.getToolNames().contains(name)) {
            throw new AssertionError("expected melonPaw tool name: " + name + ", got " + toolkit.getToolNames());
        }
    }

    private static void assertAbsent(Toolkit toolkit, String name) {
        if (toolkit.getToolNames().contains(name)) {
            throw new AssertionError("unexpected AgentScope default tool name: " + name + ", got " + toolkit.getToolNames());
        }
    }
}
