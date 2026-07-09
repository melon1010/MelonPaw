package com.melon.app.cli;

import com.melon.app.cli.context.CliOutputFormat;
import com.melon.app.cli.output.CliOutputRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CliOutputRendererTest {

    @Test
    void rendersJsonAndPlainText() {
        CliOutputRenderer renderer = new CliOutputRenderer();
        assertTrue(renderer.render("{\"ok\":true}", CliOutputFormat.JSON).contains("ok"));
        assertTrue(renderer.plain("hello").contains("hello"));
    }

    @Test
    void rendersTables() {
        CliOutputRenderer renderer = new CliOutputRenderer();
        String table = renderer.table(List.of(Map.of("name", "default", "enabled", true)), List.of("name", "enabled"));
        assertTrue(table.contains("default"));
        assertTrue(table.contains("enabled"));
    }
}
