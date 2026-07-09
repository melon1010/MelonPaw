package com.melon.app.service;

import com.melon.app.runner.ChatManager;
import com.melon.core.config.ConfigManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class HistoryStoreSelfCheck {

    private HistoryStoreSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-history-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        HistoryStore historyStore = new HistoryStore(configManager);
        ChatManager chatManager = new ChatManager(configManager, historyStore);

        Map<String, Object> state = Map.of("context", List.of(
                Map.of("id", "u1", "role", "user", "content", List.of(Map.of("type", "text", "text", "hello history"))),
                Map.of("id", "a1", "role", "assistant", "content", List.of(
                        Map.of("type", "text", "text", "using tool"),
                        Map.of("type", "tool_call", "id", "c1", "name", "execute", "input", Map.of("command", "pwd")),
                        Map.of("type", "tool_result", "id", "c1", "name", "execute", "state", "success", "output", "done")
                ))
        ));
        chatManager.saveSessionShadow("default", "console", "u1", "s1", state);
        chatManager.saveSessionShadow("default", "console", "u1", "s1", state);
        if (!historyStore.awaitIdle(5000)) {
            throw new AssertionError("history async writer did not become idle");
        }

        if (!Files.isRegularFile(home.resolve("workspaces/default/history.db"))) {
            throw new AssertionError("history.db was not created");
        }
        if (historyStore.count("default", "s1") != 3) {
            throw new AssertionError("history dedup/count mismatch: " + historyStore.count("default", "s1"));
        }
        if (historyStore.session("default", "s1", 10).size() != 3) {
            throw new AssertionError("history session query failed");
        }
        if (historyStore.range("default", 1, 10).size() != 3) {
            throw new AssertionError("history range query failed");
        }
        if (historyStore.recallTool("default", "c1", 10).size() != 2) {
            throw new AssertionError("history tool recall failed");
        }
        Map<String, Object> scroll = historyStore.scrollIndex("default", "s1");
        if (((List<?>) scroll.get("persisted_ids")).isEmpty()
                || ((Map<?, ?>) scroll.get("seq_by_id")).isEmpty()
                || ((List<?>) scroll.get("persisted_tcids")).isEmpty()) {
            throw new AssertionError("history scroll index failed: " + scroll);
        }
        if (historyStore.search("default", "hello", 10).isEmpty()) {
            throw new AssertionError("history search failed");
        }
        if (historyStore.purge("default", Instant.now().plusSeconds(60).toString()) != 3) {
            throw new AssertionError("history purge failed");
        }
        if (!historyStore.search("default", "hello", 10).isEmpty()) {
            throw new AssertionError("history FTS should not return purged rows");
        }

        Path sessionDir = home.resolve("workspaces/default/sessions/console");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("u1_synced.json"), """
                {"agent":{"state":{"context":[
                  {"id":"sync-u","role":"user","content":[{"type":"text","text":"synced question"}]},
                  {"id":"sync-a","role":"assistant","content":[{"type":"text","text":"synced answer"}]}
                ]}}}
                """);
        int syncedRows = historyStore.syncWorkspaceSessions("default");
        if (syncedRows < 2 || historyStore.count("default", "synced") != 2) {
            throw new AssertionError("session sync should import target session rows: synced=" + syncedRows);
        }
        if (historyStore.syncWorkspaceSessions("default") != 0) {
            throw new AssertionError("session sync should be idempotent");
        }
        if (historyStore.search("default", "synced", 10).isEmpty()) {
            throw new AssertionError("synced session should be searchable");
        }
        historyStore.appendSessionAsync("default", "async-session", Map.of("context", List.of(
                Map.of("id", "async-u", "role", "user", "content", List.of(Map.of("type", "text", "text", "async write")))
        )));
        if (!historyStore.awaitIdle(5000) || historyStore.count("default", "async-session") != 1) {
            throw new AssertionError("async history write did not complete");
        }

        Path db = home.resolve("workspaces/default/history.db");
        Files.writeString(db, "not a sqlite db");
        historyStore.count("default", "after-corrupt");
        if (Files.list(home.resolve("workspaces/default"))
                .noneMatch(path -> path.getFileName().toString().startsWith("history.db.corrupt-"))) {
            throw new AssertionError("corrupt history.db should be quarantined");
        }
    }
}
