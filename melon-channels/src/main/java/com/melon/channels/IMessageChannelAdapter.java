package com.melon.channels;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.melon.core.util.ValueUtils.stringValue;

public class IMessageChannelAdapter extends BasicChannelAdapter {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Future<?>> tasks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> stops = new ConcurrentHashMap<>();
    private final Map<String, String> imsgPaths = new ConcurrentHashMap<>();

    public IMessageChannelAdapter() {
        super("imessage", true, false, false, false);
    }

    @Override
    public CompletableFuture<ChannelHealth> start(String agentId,
                                                  Map<String, Object> config,
                                                  ChannelInboundDispatcher dispatcher) {
        String missing = missing(config);
        if (!missing.isBlank()) return CompletableFuture.completedFuture(health(agentId, config));
        stop(agentId).join();
        AtomicBoolean stop = new AtomicBoolean(false);
        stops.put(key(agentId), stop);
        Future<?> task = executor.submit(() -> pollLoop(agentId, new LinkedHashMap<>(config), dispatcher, stop));
        tasks.put(key(agentId), task);
        return CompletableFuture.completedFuture(health(agentId, config));
    }

    @Override
    public CompletableFuture<ChannelHealth> stop(String agentId) {
        AtomicBoolean stop = stops.remove(key(agentId));
        if (stop != null) stop.set(true);
        Future<?> task = tasks.remove(key(agentId));
        if (task != null) task.cancel(true);
        return CompletableFuture.completedFuture(ChannelHealth.of(type(), "stopped", false, true, true, "Channel stopped"));
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        boolean enabled = Boolean.TRUE.equals(config != null ? config.get("enabled") : null);
        if (!enabled) return ChannelHealth.of(type(), "stopped", false, true, true, "Channel is disabled.");
        String missing = missing(config != null ? config : Map.of());
        if (!missing.isBlank()) {
            return ChannelHealth.of(type(), "misconfigured", false, false, true, "Missing requirement: " + missing);
        }
        Future<?> task = tasks.get(key(agentId));
        boolean running = task != null && !task.isDone();
        return ChannelHealth.of(type(), running ? "running" : "configured", running, true, true,
                running ? "iMessage watcher is running." : "iMessage channel is configured.");
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String to = message.getTo() != null ? message.getTo().getId() : message.getUserId();
                String imsg = imsgPaths.computeIfAbsent(key(message.getAgentId()), ignored -> findExecutable("imsg"));
                Process process = new ProcessBuilder(imsg, "send", "--to", to, "--text", message.getText())
                        .redirectErrorStream(true)
                        .start();
                int code = process.waitFor();
                mark(message, code == 0 ? "sent" : "failed", readOutput(process));
            } catch (Exception e) {
                mark(message, "failed", e.getMessage());
            }
            return message;
        });
    }

    @Override
    public void close() {
        tasks.keySet().forEach(this::stop);
        executor.shutdownNow();
    }

    private void pollLoop(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher, AtomicBoolean stop) {
        long lastRowId = maxRowId(dbPath(config));
        double pollSeconds = doubleValue(config.get("poll_sec"), 1.0);
        while (!stop.get() && !Thread.currentThread().isInterrupted()) {
            try {
                for (Map<String, Object> row : rowsSince(dbPath(config), lastRowId)) {
                    lastRowId = longValue(row.get("rowid"), lastRowId);
                    if ("1".equals(stringValue(row.get("is_from_me")))) continue;
                    String text = stringValue(row.get("text"));
                    String prefix = stringValue(config.get("bot_prefix"));
                    if (text.isBlank() || (!prefix.isBlank() && text.startsWith(prefix))) continue;
                    String sender = stringValue(row.get("sender"));
                    if (sender.isBlank()) continue;
                    ChannelInboundMessage inbound = new ChannelInboundMessage();
                    inbound.setAgentId(agentId);
                    inbound.setChannel(type());
                    inbound.setUserId(sender);
                    inbound.setSessionId("imessage:" + sender);
                    inbound.setContent(text);
                    inbound.setChannelMeta(row);
                    inbound.setReplyTo(new ChannelAddress("imessage", sender, Map.of("rowid", row.get("rowid"))));
                    dispatcher.dispatch(inbound, 20);
                }
            } catch (Exception ignored) {
                // iMessage DB can be locked by Messages; retry on next poll.
            }
            sleep((long) Math.max(200, pollSeconds * 1000));
        }
    }

    private long maxRowId(Path db) {
        String output = sqlite(db, "SELECT IFNULL(MAX(ROWID),0) FROM message;");
        return longValue(output.trim(), 0L);
    }

    private java.util.List<Map<String, Object>> rowsSince(Path db, long lastRowId) {
        String sql = """
                SELECT m.ROWID,
                       replace(replace(IFNULL(m.text,''), char(10), ' '), char(13), ' ') as text,
                       m.is_from_me,
                       c.ROWID as chat_rowid,
                       IFNULL(h.id,'') as sender
                FROM message m
                JOIN chat_message_join cmj ON cmj.message_id = m.ROWID
                JOIN chat c ON c.ROWID = cmj.chat_id
                LEFT JOIN handle h ON h.ROWID = m.handle_id
                WHERE m.ROWID > %d
                ORDER BY m.ROWID ASC;
                """.formatted(lastRowId);
        String output = sqlite(db, sql);
        java.util.ArrayList<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\t", -1);
            if (parts.length < 5) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rowid", parts[0]);
            row.put("text", parts[1]);
            row.put("is_from_me", parts[2]);
            row.put("chat_rowid", parts[3]);
            row.put("sender", parts[4]);
            rows.add(row);
        }
        return rows;
    }

    private String sqlite(Path db, String sql) {
        try {
            Process process = new ProcessBuilder("sqlite3", "-readonly", "-separator", "\t", db.toString(), sql)
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(process);
            int code = process.waitFor();
            if (code != 0) throw new IllegalStateException(output);
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("sqlite3 failed: " + e.getMessage(), e);
        }
    }

    private String missing(Map<String, Object> config) {
        java.util.ArrayList<String> issues = new java.util.ArrayList<>();
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) issues.add("macOS");
        Path db = dbPath(config);
        if (!Files.isRegularFile(db)) issues.add("db_path");
        if (findExecutable("sqlite3").isBlank()) issues.add("sqlite3");
        String imsg = findExecutable("imsg");
        if (imsg.isBlank()) issues.add("imsg");
        return String.join(", ", issues);
    }

    private Path dbPath(Map<String, Object> config) {
        String value = stringValue(config.get("db_path"), "~/Library/Messages/chat.db");
        if (value.startsWith("~")) value = System.getProperty("user.home") + value.substring(1);
        return Path.of(value).toAbsolutePath().normalize();
    }

    private String findExecutable(String name) {
        try {
            Process process = new ProcessBuilder("/usr/bin/which", name).redirectErrorStream(true).start();
            String output = readOutput(process).trim();
            return process.waitFor() == 0 ? output : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String readOutput(Process process) throws java.io.IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!out.isEmpty()) out.append('\n');
                out.append(line);
            }
            return out.toString();
        }
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String key(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }

    private void mark(ChannelOutboundMessage message, String status, String detail) {
        Map<String, Object> meta = new LinkedHashMap<>(message.getMeta());
        meta.put("delivery_status", status);
        meta.put("delivery_detail", detail != null ? detail : "");
        message.setMeta(meta);
    }
}
