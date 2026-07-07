package com.melon.app.service;

import com.melon.core.config.ConfigManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class InboxStoreSelfCheck {

    private InboxStoreSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-inbox-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        InboxStore store = new InboxStore(configManager);

        store.createTrace("run-1", Map.of("job_id", "job-1"));
        store.appendTraceText("run-1", "assistant", "done");
        store.finalizeTrace("run-1", "success", null);
        Map<String, Object> event = store.appendEvent(
                "agent-a", "cron", "job-1", "cron_result", "success", "info",
                "Cron result: job", "done", Map.of("run_id", "run-1"));

        List<Map<String, Object>> unread = store.listEvents(10, 0, "cron", "success", "agent-a", true);
        if (unread.size() != 1 || !event.get("id").equals(unread.get(0).get("id"))) {
            throw new AssertionError("event filter failed: " + unread);
        }
        if (store.markRead(List.of(String.valueOf(event.get("id")))) != 1) {
            throw new AssertionError("markRead failed");
        }
        if (!store.listEvents(10, 0, null, null, null, true).isEmpty()) {
            throw new AssertionError("unread filter failed");
        }
        Map<String, Object> trace = store.getTrace("run-1");
        if (!trace.toString().contains("done")) {
            throw new AssertionError("trace missing text: " + trace);
        }
        Map<String, Object> deleted = store.deleteEvent(String.valueOf(event.get("id")));
        if (!Boolean.TRUE.equals(deleted.get("deleted")) || !Boolean.TRUE.equals(deleted.get("trace_deleted"))) {
            throw new AssertionError("delete failed: " + deleted);
        }
    }
}
