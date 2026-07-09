package com.melon.app.cli;

import com.melon.app.cli.spec.CliCommandSpecs;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliSpecTest {

    @Test
    void specsDeclareStableHttpContracts() {
        assertEquals("GET", CliCommandSpecs.AGENTS_LIST.method());
        assertEquals("/api/agents", CliCommandSpecs.AGENTS_LIST.path());
        assertEquals("POST", CliCommandSpecs.CRON_CREATE.method());
        assertEquals("/api/cron/jobs", CliCommandSpecs.CRON_CREATE.path());
        assertEquals("PUT", CliCommandSpecs.ENV_SET.method());
        assertEquals("/api/envs/{key}", CliCommandSpecs.ENV_SET.path());
        assertEquals("POST", CliCommandSpecs.AGENTS_CREATE.method());
        assertEquals("/api/agents", CliCommandSpecs.AGENTS_CREATE.path());
        assertEquals("PATCH", CliCommandSpecs.AGENTS_TOGGLE.method());
        assertEquals("/api/agents/{id}/toggle", CliCommandSpecs.AGENTS_TOGGLE.path());
        assertEquals("PUT", CliCommandSpecs.MODELS_ACTIVE_SET.method());
        assertEquals("/api/models/active", CliCommandSpecs.MODELS_ACTIVE_SET.path());
        assertEquals("POST", CliCommandSpecs.SKILLS_ENABLE.method());
        assertEquals("/api/skills/{name}/enable", CliCommandSpecs.SKILLS_ENABLE.path());
        assertEquals("DELETE", CliCommandSpecs.PLUGIN_UNINSTALL.method());
        assertEquals("/api/plugins/{pluginId}", CliCommandSpecs.PLUGIN_UNINSTALL.path());
        assertEquals("PUT", CliCommandSpecs.CHANNELS_CONFIG.method());
        assertEquals("/api/config/channels/{channel}", CliCommandSpecs.CHANNELS_CONFIG.path());
        assertEquals("POST", CliCommandSpecs.MODELS_LOCAL_DOWNLOAD.method());
        assertEquals("/api/local-models/models/download", CliCommandSpecs.MODELS_LOCAL_DOWNLOAD.path());
        assertEquals("POST", CliCommandSpecs.SKILLS_INSTALL_START.method());
        assertEquals("/api/skills/hub/install/start", CliCommandSpecs.SKILLS_INSTALL_START.path());
        assertEquals("POST", CliCommandSpecs.CONSOLE_TASK_SUBMIT.method());
        assertEquals("/api/console/chat/task", CliCommandSpecs.CONSOLE_TASK_SUBMIT.path());
        assertEquals("GET", CliCommandSpecs.BACKEND_LOGS.method());
        assertEquals("/api/console/debug/backend-logs?lines={lines}", CliCommandSpecs.BACKEND_LOGS.path());
        assertEquals("POST", CliCommandSpecs.SHUTDOWN.method());
        assertEquals("/api/agent/shutdown", CliCommandSpecs.SHUTDOWN.path());
    }

    @Test
    void specsExpandPathParameters() {
        assertEquals("/api/envs/OPENAI_API_KEY",
                CliCommandSpecs.ENV_GET.expandPath(Map.of("key", "OPENAI_API_KEY")));
        assertTrue(CliCommandSpecs.CRON_DELETE.successStatuses().contains(204));
    }
}
