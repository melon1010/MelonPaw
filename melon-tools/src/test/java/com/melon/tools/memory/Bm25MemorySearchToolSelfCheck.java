package com.melon.tools.memory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Bm25MemorySearchToolSelfCheck {

    private Bm25MemorySearchToolSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path workspace = Files.createTempDirectory("melon-bm25-memory");
        Files.writeString(workspace.resolve("MEMORY.md"), """
                Project uses ReAct orchestration with plain phrase memory search.
                handleWebSocketReconnect fixed the reconnect path after socket disconnect.
                """);
        Files.createDirectories(workspace.resolve("memory"));
        Files.writeString(workspace.resolve("memory").resolve("2026-07-10.md"), """
                Tool guard parity was implemented for dangerous shell commands.
                ReMeLight search combines BM25 keyword recall with optional vectors.
                """);

        Bm25MemorySearchTool tool = new Bm25MemorySearchTool(workspace);
        String identifierHit = tool.memorySearch(null, "websocket reconnect", 3, 0.0);
        assertContains(identifierHit, "handleWebSocketReconnect");
        String bm25Hit = tool.memorySearch(null, "keyword vectors", 3, 0.0);
        assertContains(bm25Hit, "ReMeLight search combines BM25");
        String empty = tool.memorySearch(null, "not-present-token", 3, 0.0);
        assertContains(empty, "(no memory results)");
    }

    private static void assertContains(String text, String expected) {
        if (text == null || !text.contains(expected)) {
            throw new AssertionError("expected `" + expected + "` in:\n" + text);
        }
    }
}
