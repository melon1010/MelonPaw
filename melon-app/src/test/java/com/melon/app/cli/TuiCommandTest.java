package com.melon.app.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TuiCommandTest {

    @Test
    void requestContextCarriesProjectAndSession() {
        Path project = Path.of("/tmp/project").toAbsolutePath().normalize();

        Map<String, Object> context = TuiCommand.requestContext("default", "session-1", project);

        assertEquals("default", context.get("root_agent_id"));
        assertEquals("session-1", context.get("root_session_id"));
        assertEquals(project.toString(), context.get("project_dir"));
        assertEquals(project.toString(), context.get("working_dir"));
        assertEquals("tui", context.get("source"));
    }
}
