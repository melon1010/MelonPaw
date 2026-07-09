package com.melon.app.service;

import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import com.melon.core.config.LightContextConfig;
import com.melon.core.util.JsonUtils;
import com.melon.core.util.SafePathUtil;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.melon.core.util.ValueUtils.stringValue;

@Component
public class HistoryStore {

    private static final Logger log = LoggerFactory.getLogger(HistoryStore.class);
    private static final Set<String> RECALL_TOOL_NAMES = Set.of("recall_history_python", "execute_python");

    private final ConfigManager configManager;
    private final Set<Path> initializedDbs = ConcurrentHashMap.newKeySet();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "melon-history-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private final Object idleMonitor = new Object();

    public HistoryStore(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @PreDestroy
    public void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }

    public Path historyPath(String agentId) {
        AgentConfig agent = configManager.getConfig().getAgent(normalizeAgentId(agentId));
        LightContextConfig light = agent != null && agent.getLightContextConfig() != null
                ? agent.getLightContextConfig()
                : new LightContextConfig();
        String filename = light.getScrollConfig() != null && light.getScrollConfig().getDbFilename() != null
                ? light.getScrollConfig().getDbFilename()
                : "history.db";
        return SafePathUtil.resolveSafe(configManager.resolveWorkspaceDir(normalizeAgentId(agentId)), filename);
    }

    public void appendSession(String agentId, String sessionId, Map<String, Object> state) {
        if (!scrollEnabled(agentId)) return;
        Object raw = state != null ? state.get("context") : null;
        if (!(raw instanceof List<?> context) || context.isEmpty()) return;
        List<Entry> entries = entries(normalizeAgentId(agentId), valueOrDefault(sessionId, "default"), context);
        if (entries.isEmpty()) return;
        Path db = historyPath(agentId);
        try (Connection conn = open(db)) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                for (Entry entry : entries) {
                    append(conn, entry);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            log.warn("history write-through failed: agent={}, session={}", agentId, sessionId, e);
        }
    }

    public void appendSessionAsync(String agentId, String sessionId, Map<String, Object> state) {
        pendingWrites.incrementAndGet();
        try {
            writer.execute(() -> {
                try {
                    appendSession(agentId, sessionId, state);
                } finally {
                    markIdle();
                }
            });
        } catch (RejectedExecutionException e) {
            markIdle();
            log.warn("history async writer rejected task: agent={}, session={}", agentId, sessionId, e);
        }
    }

    public boolean awaitIdle(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMillis);
        synchronized (idleMonitor) {
            while (pendingWrites.get() > 0) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) return false;
                try {
                    idleMonitor.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    public int syncWorkspaceSessions(String agentId) {
        if (!scrollEnabled(agentId)) return 0;
        Path sessionsDir = configManager.resolveWorkspaceDir(normalizeAgentId(agentId)).resolve("sessions");
        if (!Files.isDirectory(sessionsDir)) return 0;
        int[] inserted = {0};
        try (var stream = Files.find(sessionsDir, 3,
                (path, attrs) -> attrs.isRegularFile() && path.getFileName().toString().endsWith(".json"))) {
            stream.forEach(path -> inserted[0] += syncSessionFile(agentId, path));
        } catch (Exception e) {
            log.warn("history session sync failed: agent={}, dir={}", agentId, sessionsDir, e);
        }
        return inserted[0];
    }

    public int purgeExpired(String agentId) {
        AgentConfig agent = configManager.getConfig().getAgent(normalizeAgentId(agentId));
        int days = agent != null && agent.getLightContextConfig() != null
                && agent.getLightContextConfig().getScrollConfig() != null
                ? agent.getLightContextConfig().getScrollConfig().getHistoryRetentionDays()
                : 30;
        if (days <= 0) return 0;
        String before = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)
                .format(Instant.now().minus(days, ChronoUnit.DAYS));
        return purge(agentId, before);
    }

    public int count(String agentId, String sessionId) {
        try (Connection conn = open(historyPath(agentId));
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM conversation_history WHERE session_id = ?")) {
            ps.setString(1, valueOrDefault(sessionId, "default"));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            log.warn("history count failed: agent={}, session={}", agentId, sessionId, e);
            return 0;
        }
    }

    public List<Map<String, Object>> session(String agentId, String sessionId, int limit) {
        String sql = """
                SELECT seq, kind, role, name, headline, content, tool_call_id, created_at
                FROM conversation_history
                WHERE agent_id = ? AND session_id = ?
                ORDER BY seq LIMIT ?
                """;
        try (Connection conn = open(historyPath(agentId));
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeAgentId(agentId));
            ps.setString(2, valueOrDefault(sessionId, "default"));
            ps.setInt(3, Math.max(1, limit));
            return rows(ps.executeQuery());
        } catch (Exception e) {
            log.warn("history session query failed: agent={}, session={}", agentId, sessionId, e);
            return List.of();
        }
    }

    public List<Map<String, Object>> range(String agentId, long lo, long hi) {
        String sql = """
                SELECT seq, session_id, kind, role, name, headline, content, tool_call_id, tool_input, tool_state, created_at
                FROM conversation_history
                WHERE agent_id = ? AND seq BETWEEN ? AND ?
                ORDER BY seq
                """;
        try (Connection conn = open(historyPath(agentId));
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeAgentId(agentId));
            ps.setLong(2, Math.min(lo, hi));
            ps.setLong(3, Math.max(lo, hi));
            return rows(ps.executeQuery());
        } catch (Exception e) {
            log.warn("history range query failed: agent={}, lo={}, hi={}", agentId, lo, hi, e);
            return List.of();
        }
    }

    public List<Map<String, Object>> recallTool(String agentId, String toolCallId, int limit) {
        if (toolCallId == null || toolCallId.isBlank()) return List.of();
        String sql = """
                SELECT seq, session_id, kind, role, name, content, tool_call_id, tool_input, tool_state, created_at
                FROM conversation_history
                WHERE agent_id = ? AND tool_call_id = ?
                ORDER BY seq LIMIT ?
                """;
        try (Connection conn = open(historyPath(agentId));
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeAgentId(agentId));
            ps.setString(2, toolCallId);
            ps.setInt(3, Math.max(1, limit));
            return rows(ps.executeQuery());
        } catch (Exception e) {
            log.warn("history tool recall failed: agent={}, tool_call_id={}", agentId, toolCallId, e);
            return List.of();
        }
    }

    public Map<String, Object> scrollIndex(String agentId, String sessionId) {
        String sql = """
                SELECT seq, dedup_key, tool_call_id
                FROM conversation_history
                WHERE agent_id = ? AND session_id = ?
                ORDER BY seq
                """;
        try (Connection conn = open(historyPath(agentId));
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeAgentId(agentId));
            ps.setString(2, valueOrDefault(sessionId, "default"));
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, long[]> ranges = new LinkedHashMap<>();
                Set<String> toolCallIds = new HashSet<>();
                while (rs.next()) {
                    long seq = rs.getLong("seq");
                    String id = messageId(rs.getString("dedup_key"));
                    if (!id.isBlank()) {
                        long[] range = ranges.computeIfAbsent(id, ignored -> new long[]{seq, seq});
                        range[0] = Math.min(range[0], seq);
                        range[1] = Math.max(range[1], seq);
                    }
                    String toolCallId = rs.getString("tool_call_id");
                    if (toolCallId != null && !toolCallId.isBlank()) toolCallIds.add(toolCallId);
                }
                if (ranges.isEmpty()) return Map.of();
                Map<String, Object> seqById = new LinkedHashMap<>();
                Map<String, Object> modelTurnSeq = new LinkedHashMap<>();
                Map<String, Object> modelTurnNblk = new LinkedHashMap<>();
                for (var entry : ranges.entrySet()) {
                    long[] range = entry.getValue();
                    seqById.put(entry.getKey(), List.of(range[0], range[1]));
                    modelTurnSeq.put(entry.getKey(), range[0]);
                    modelTurnNblk.put(entry.getKey(), Math.max(1, range[1] - range[0] + 1));
                }
                Map<String, Object> scroll = new LinkedHashMap<>();
                scroll.put("persisted_ids", new ArrayList<>(ranges.keySet()));
                scroll.put("persisted_tcids", new ArrayList<>(toolCallIds));
                scroll.put("synthetic_ids", List.of());
                scroll.put("seq_by_id", seqById);
                scroll.put("model_turn_seq", modelTurnSeq);
                scroll.put("model_turn_nblk", modelTurnNblk);
                scroll.put("leaf_by_id", Map.of());
                scroll.put("index", Map.of("session_id", valueOrDefault(sessionId, "default"), "agent_id", normalizeAgentId(agentId), "tiers", List.of()));
                return scroll;
            }
        } catch (Exception e) {
            log.debug("history scroll index unavailable: agent={}, session={}", agentId, sessionId, e);
            return Map.of();
        }
    }

    public List<Map<String, Object>> search(String agentId, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        try (Connection conn = open(historyPath(agentId))) {
            if (hasFts(conn)) {
                String match = ftsQuery(query);
                if (!match.isBlank()) {
                    String sql = """
                            SELECT ch.seq, ch.session_id, ch.kind, ch.role, ch.name, ch.headline, ch.content
                            FROM conversation_history_fts
                            JOIN conversation_history ch ON ch.seq = conversation_history_fts.rowid
                            WHERE conversation_history_fts MATCH ?
                              AND ch.agent_id = ?
                              AND (ch.name IS NULL OR ch.name NOT IN ('recall_history_python', 'execute_python'))
                            ORDER BY bm25(conversation_history_fts) LIMIT ?
                            """;
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, match);
                        ps.setString(2, normalizeAgentId(agentId));
                        ps.setInt(3, Math.max(1, limit));
                        return rows(ps.executeQuery());
                    } catch (SQLException ignored) {
                        // ponytail: malformed FTS input falls through to LIKE; add a real parser if search quality matters.
                    }
                }
            }
            return searchLike(conn, normalizeAgentId(agentId), query, limit);
        } catch (Exception e) {
            log.warn("history search failed: agent={}, query={}", agentId, query, e);
            return List.of();
        }
    }

    public int purge(String agentId, String beforeIso) {
        String where = "agent_id = ? AND created_at IS NOT NULL AND created_at < ?";
        try (Connection conn = open(historyPath(agentId))) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                List<Map<String, Object>> doomed;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT seq, content FROM conversation_history WHERE " + where)) {
                    ps.setString(1, normalizeAgentId(agentId));
                    ps.setString(2, beforeIso);
                    doomed = rows(ps.executeQuery());
                }
                if (doomed.isEmpty()) {
                    conn.commit();
                    return 0;
                }
                if (hasFts(conn)) {
                    try (PreparedStatement fts = conn.prepareStatement(
                            "INSERT INTO conversation_history_fts(conversation_history_fts, rowid, content) VALUES('delete', ?, ?)")) {
                        for (Map<String, Object> row : doomed) {
                            fts.setObject(1, row.get("seq"));
                            fts.setString(2, valueOrDefault(stringValue(row.get("content")), ""));
                            fts.addBatch();
                        }
                        fts.executeBatch();
                    } catch (SQLException e) {
                        log.debug("history FTS purge sync skipped", e);
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM conversation_history WHERE " + where)) {
                    ps.setString(1, normalizeAgentId(agentId));
                    ps.setString(2, beforeIso);
                    int deleted = ps.executeUpdate();
                    conn.commit();
                    return deleted;
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            log.warn("history purge failed: agent={}, before={}", agentId, beforeIso, e);
            return 0;
        }
    }

    private Connection open(Path db) throws SQLException, IOException {
        Files.createDirectories(db.getParent());
        Path key = db.toAbsolutePath().normalize();
        boolean needsInit = !initializedDbs.contains(key) || !Files.exists(db);
        if (!needsInit) {
            try {
                return openChecked(db, false);
            } catch (SQLException e) {
                if (!isCorruption(e)) throw e;
                synchronized (initializedDbs) {
                    quarantine(db);
                    initializedDbs.remove(key);
                    Connection conn = openChecked(db, true);
                    initializedDbs.add(key);
                    return conn;
                }
            }
        }
        synchronized (initializedDbs) {
            if (initializedDbs.contains(key) && Files.exists(db)) {
                return openChecked(db, false);
            }
            try {
                Connection conn = openChecked(db, true);
                initializedDbs.add(key);
                return conn;
            } catch (SQLException e) {
                if (!isCorruption(e)) throw e;
                quarantine(db);
                initializedDbs.remove(key);
                Connection conn = openChecked(db, true);
                initializedDbs.add(key);
                return conn;
            }
        }
    }

    private Connection openChecked(Path db, boolean checkAndInit) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
            if (checkAndInit) {
                try (ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
                    if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                        throw new SQLException("quick_check failed", "SQLITE_CORRUPT", 11);
                    }
                }
                initSchema(st);
            }
            return conn;
        } catch (SQLException e) {
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
            throw e;
        }
    }

    private void initSchema(Statement st) throws SQLException {
        st.execute("""
                CREATE TABLE IF NOT EXISTS conversation_history (
                    seq          INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id   TEXT NOT NULL,
                    agent_id     TEXT,
                    kind         TEXT NOT NULL,
                    role         TEXT,
                    name         TEXT,
                    content      TEXT,
                    tool_call_id TEXT,
                    tool_input   TEXT,
                    tool_state   TEXT,
                    headline     TEXT,
                    blocks       TEXT,
                    metadata     TEXT,
                    created_at   TEXT,
                    dedup_key    TEXT
                )
                """);
        st.execute("CREATE INDEX IF NOT EXISTS ch_session ON conversation_history(session_id)");
        st.execute("CREATE INDEX IF NOT EXISTS ch_agent ON conversation_history(agent_id)");
        st.execute("CREATE INDEX IF NOT EXISTS ch_kind ON conversation_history(kind)");
        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_dedup ON conversation_history(session_id, dedup_key)");
        try {
            boolean existed = tableExists(st, "conversation_history_fts");
            st.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS conversation_history_fts
                    USING fts5(content, content='conversation_history', content_rowid='seq', tokenize='porter unicode61')
                    """);
            if (!existed) {
                st.execute("INSERT INTO conversation_history_fts(conversation_history_fts) VALUES('rebuild')");
            }
        } catch (SQLException e) {
            log.debug("SQLite FTS5 unavailable; history search will use LIKE", e);
        }
    }

    private void append(Connection conn, Entry entry) throws SQLException {
        String sql = """
                INSERT INTO conversation_history
                (session_id, agent_id, kind, role, name, content, tool_call_id, tool_input,
                 tool_state, headline, blocks, metadata, created_at, dedup_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id, dedup_key) DO NOTHING
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, entry.sessionId());
            ps.setString(2, entry.agentId());
            ps.setString(3, entry.kind());
            ps.setString(4, entry.role());
            ps.setString(5, entry.name());
            ps.setString(6, entry.content());
            ps.setString(7, entry.toolCallId());
            ps.setString(8, entry.toolInput());
            ps.setString(9, entry.toolState());
            ps.setString(10, entry.headline());
            ps.setString(11, entry.blocks());
            ps.setString(12, entry.metadata());
            ps.setString(13, entry.createdAt());
            ps.setString(14, entry.dedupKey());
            int changed = ps.executeUpdate();
            String name = entry.name();
            if (changed == 0 || !hasFts(conn) || (name != null && RECALL_TOOL_NAMES.contains(name))) return;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    try (PreparedStatement fts = conn.prepareStatement(
                            "INSERT INTO conversation_history_fts(rowid, content) VALUES (?, ?)")) {
                        fts.setLong(1, keys.getLong(1));
                        fts.setString(2, valueOrDefault(entry.content(), ""));
                        fts.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                log.debug("history FTS insert skipped", e);
            }
        }
    }

    private List<Entry> entries(String agentId, String sessionId, List<?> context) {
        List<Entry> entries = new ArrayList<>();
        String now = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC).format(Instant.now());
        int index = 0;
        for (Object item : context) {
            if (!(item instanceof Map<?, ?> rawMessage)) continue;
            Map<String, Object> message = asMap(rawMessage);
            String role = valueOrDefault(stringValue(message.get("role")), "assistant");
            String messageId = valueOrDefault(stringValue(message.get("id")), Integer.toHexString(JsonUtils.toJson(message).hashCode()));
            Object contentRaw = message.get("content");
            List<Map<String, Object>> blocks = blocks(contentRaw);
            List<Map<String, Object>> nonResults = blocks.stream()
                    .filter(block -> !"tool_result".equals(stringValue(block.get("type"))))
                    .toList();
            List<Map<String, Object>> results = blocks.stream()
                    .filter(block -> "tool_result".equals(stringValue(block.get("type"))))
                    .toList();
            if (!nonResults.isEmpty() || results.isEmpty()) {
                entries.add(entry(agentId, sessionId, role, nonResults, message, messageId + ":turn:" + index, now));
            }
            int resultIndex = 0;
            for (Map<String, Object> result : results) {
                entries.add(toolResult(agentId, sessionId, role, result, messageId + ":tool_result:" + resultIndex++, now));
            }
            index++;
        }
        return entries;
    }

    private Entry entry(String agentId, String sessionId, String role, List<Map<String, Object>> blocks,
                        Map<String, Object> message, String dedupKey, String createdAt) {
        String name = null;
        String toolCallId = null;
        Object toolInput = null;
        for (Map<String, Object> block : blocks) {
            if ("tool_call".equals(stringValue(block.get("type")))) {
                name = stringValue(block.get("name"));
                toolCallId = stringValue(block.get("id"));
                toolInput = block.get("input");
            }
        }
        String text = flatten(blocks.isEmpty() ? message.get("content") : blocks);
        String kind = "assistant".equals(role) ? "model_turn" : "context_msg";
        return new Entry(sessionId, agentId, kind, role, blankToNull(name), blankToNull(text),
                blankToNull(toolCallId), jsonOrNull(toolInput), null, null, jsonOrNull(blocks),
                null, createdAt, dedupKey);
    }

    private Entry toolResult(String agentId, String sessionId, String role, Map<String, Object> block,
                             String dedupKey, String createdAt) {
        String content = flatten(block.get("output"));
        return new Entry(sessionId, agentId, "tool_result", role, blankToNull(stringValue(block.get("name"))),
                blankToNull(content), blankToNull(stringValue(block.get("id"))), null,
                blankToNull(stringValue(block.get("state"))), null, jsonOrNull(List.of(block)), null, createdAt, dedupKey);
    }

    private List<Map<String, Object>> blocks(Object content) {
        if (!(content instanceof List<?> list)) return List.of();
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) blocks.add(asMap(raw));
        }
        return blocks;
    }

    private String flatten(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> block = asMap(raw);
                    String text = stringValue(block.get("text"));
                    if (text.isBlank()) text = stringValue(block.get("output"));
                    if (text.isBlank() && block.get("file_url") != null) text = "[file: " + block.get("file_url") + "]";
                    if (text.isBlank() && block.get("image_url") != null) text = "[image: " + block.get("image_url") + "]";
                    if (!text.isBlank()) parts.add(text);
                } else if (item != null) {
                    parts.add(String.valueOf(item));
                }
            }
            return String.join("\n", parts);
        }
        return JsonUtils.toJson(value);
    }

    private List<Map<String, Object>> searchLike(Connection conn, String agentId, String query, int limit) throws SQLException {
        String sql = """
                SELECT seq, session_id, kind, role, name, headline, content
                FROM conversation_history
                WHERE agent_id = ? AND content LIKE ?
                  AND (name IS NULL OR name NOT IN ('recall_history_python', 'execute_python'))
                ORDER BY seq DESC LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setString(2, "%" + query + "%");
            ps.setInt(3, Math.max(1, limit));
            return rows(ps.executeQuery());
        }
    }

    private int syncSessionFile(String agentId, Path file) {
        Map<String, Object> raw = JsonUtils.loadAsMap(file);
        Map<String, Object> state = stateFromSessionShadow(raw);
        Object context = state.get("context");
        if (!(context instanceof List<?> list) || list.isEmpty()) return 0;
        String sessionId = sessionIdFromFile(file);
        int before = count(agentId, sessionId);
        appendSession(agentId, sessionId, state);
        return Math.max(0, count(agentId, sessionId) - before);
    }

    private Map<String, Object> stateFromSessionShadow(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Object agentRaw = raw.get("agent");
        if (agentRaw instanceof Map<?, ?> agentMap) {
            Object stateRaw = asMap(agentMap).get("state");
            if (stateRaw instanceof Map<?, ?> stateMap) return asMap(stateMap);
        }
        return raw;
    }

    private String sessionIdFromFile(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".json")) name = name.substring(0, name.length() - ".json".length());
        int marker = name.indexOf('_');
        return marker >= 0 && marker + 1 < name.length() ? name.substring(marker + 1) : name;
    }

    private boolean hasFts(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='conversation_history_fts'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private List<Map<String, Object>> rows(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        int cols = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= cols; i++) {
                row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private String ftsQuery(String query) {
        List<String> terms = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[\\p{L}\\p{N}_]+").matcher(query);
        while (matcher.find()) terms.add("\"" + matcher.group() + "\"");
        return String.join(" OR ", terms);
    }

    private boolean tableExists(Statement st, String table) throws SQLException {
        try (PreparedStatement ps = st.getConnection().prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean isCorruption(SQLException e) {
        if (e.getErrorCode() == 11 || e.getErrorCode() == 26) return true;
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return message.contains("quick_check failed")
                || message.contains("database disk image is malformed")
                || message.contains("file is not a database")
                || message.contains("database corruption");
    }

    private void markIdle() {
        if (pendingWrites.decrementAndGet() <= 0) {
            synchronized (idleMonitor) {
                idleMonitor.notifyAll();
            }
        }
    }

    private boolean scrollEnabled(String agentId) {
        AgentConfig agent = configManager.getConfig().getAgent(normalizeAgentId(agentId));
        if (agent == null || agent.getLightContextConfig() == null) return true;
        return "scroll".equals(agent.getLightContextConfig().getStrategy());
    }

    private String messageId(String dedupKey) {
        if (dedupKey == null || dedupKey.isBlank()) return "";
        int marker = dedupKey.indexOf(':');
        return marker > 0 ? dedupKey.substring(0, marker) : dedupKey;
    }

    private void quarantine(Path db) {
        String suffix = ".corrupt-" + System.currentTimeMillis();
        for (String ext : List.of("", "-wal", "-shm")) {
            Path source = Path.of(db + ext);
            if (!Files.exists(source)) continue;
            try {
                Files.move(source, Path.of(source + suffix));
            } catch (IOException ignored) {
            }
        }
    }

    private Map<String, Object> asMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String jsonOrNull(Object value) {
        return value == null ? null : JsonUtils.toJson(value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalizeAgentId(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }

    private record Entry(String sessionId, String agentId, String kind, String role, String name, String content,
                         String toolCallId, String toolInput, String toolState, String headline, String blocks,
                         String metadata, String createdAt, String dedupKey) {
    }
}
