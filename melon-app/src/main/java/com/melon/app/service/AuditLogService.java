package com.melon.app.service;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final ConfigManager configManager;

    public AuditLogService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void recordApproval(String agentId, String sessionId, String toolName, Object target,
                               boolean approved, String reason, Map<String, Object> extra) {
        try (Connection conn = open(dbPath(agentId));
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO audit_events
                     (ts, workspace_dir, agent_id, session_id, tool_name, target, decision, reason, extra)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            String aid = normalizeAgentId(agentId);
            ps.setLong(1, Instant.now().toEpochMilli());
            ps.setString(2, configManager.resolveWorkspaceDir(aid).toString());
            ps.setString(3, aid);
            ps.setString(4, sessionId == null || sessionId.isBlank() ? "default" : sessionId);
            ps.setString(5, toolName == null || toolName.isBlank() ? "unknown" : toolName);
            ps.setString(6, target == null ? "" : JsonUtils.toJson(target));
            ps.setString(7, approved ? "allow" : "deny");
            ps.setString(8, reason == null ? "" : reason);
            ps.setString(9, JsonUtils.toJson(extra == null ? Map.of() : extra));
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("audit write failed: agent={}, session={}, tool={}", agentId, sessionId, toolName, e);
        }
    }

    public List<Map<String, Object>> query(String agentId, String sessionId, String toolName,
                                           String decision, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_events WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (agentId != null && !agentId.isBlank()) {
            sql.append(" AND agent_id = ?");
            args.add(normalizeAgentId(agentId));
        }
        if (sessionId != null && !sessionId.isBlank()) {
            sql.append(" AND session_id = ?");
            args.add(sessionId);
        }
        if (toolName != null && !toolName.isBlank()) {
            sql.append(" AND tool_name = ?");
            args.add(toolName);
        }
        if (decision != null && !decision.isBlank()) {
            sql.append(" AND decision = ?");
            args.add(decision);
        }
        sql.append(" ORDER BY ts DESC LIMIT ?");
        args.add(Math.max(1, limit));
        try (Connection conn = open(dbPath(agentId));
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            return rows(ps.executeQuery());
        } catch (Exception e) {
            log.warn("audit query failed: agent={}", agentId, e);
            return List.of();
        }
    }

    public int purge(String agentId, long beforeMillis) {
        try (Connection conn = open(dbPath(agentId));
             PreparedStatement ps = conn.prepareStatement("DELETE FROM audit_events WHERE ts < ?")) {
            ps.setLong(1, beforeMillis);
            return ps.executeUpdate();
        } catch (Exception e) {
            log.warn("audit purge failed: agent={}", agentId, e);
            return 0;
        }
    }

    private Path dbPath(String agentId) {
        return configManager.resolveWorkspaceDir(normalizeAgentId(agentId)).resolve("governance").resolve("audit.db");
    }

    private Connection open(Path db) throws SQLException, IOException {
        Files.createDirectories(db.getParent());
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS audit_events (
                        ts            INTEGER NOT NULL,
                        workspace_dir TEXT NOT NULL,
                        agent_id      TEXT NOT NULL,
                        session_id    TEXT NOT NULL,
                        tool_name     TEXT NOT NULL,
                        target        TEXT NOT NULL,
                        decision      TEXT NOT NULL,
                        reason        TEXT NOT NULL DEFAULT '',
                        extra         TEXT NOT NULL DEFAULT '{}'
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_events(ts)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_workspace ON audit_events(workspace_dir)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_agent ON audit_events(agent_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_tool ON audit_events(tool_name)");
            return conn;
        } catch (SQLException e) {
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
            throw e;
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

    private String normalizeAgentId(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }
}
