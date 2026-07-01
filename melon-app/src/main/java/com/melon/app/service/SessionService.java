package com.melon.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

/**
 * Service for session management.
 * Handles session state persistence and retrieval via JSON files.
 * Corresponds to Python session management (SafeJSONSession).
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path sessionsDir;

    public SessionService(ConfigManager configManager) {
        this.sessionsDir = configManager.resolveHomeDir().resolve("sessions");
        try {
            Files.createDirectories(sessionsDir);
        } catch (Exception e) {
            log.warn("Failed to create sessions directory: {}", sessionsDir);
        }
    }

    /**
     * Lists all sessions for an agent.
     * Scans the agent-specific subdirectory for .json files.
     */
    public List<Map<String, Object>> listSessions(String agentId) {
        List<Map<String, Object>> sessions = new ArrayList<>();
        Path agentDir = sessionsDir.resolve(agentId);
        if (!Files.exists(agentDir)) {
            return sessions;
        }
        try (Stream<Path> entries = Files.list(agentDir)) {
            entries
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> session = objectMapper.readValue(p.toFile(), Map.class);
                        sessions.add(session);
                    } catch (IOException e) {
                        log.warn("Failed to read session file: {}", p);
                    }
                });
        } catch (IOException e) {
            log.error("Failed to list sessions for agent: {}", agentId, e);
        }
        sessions.sort((a, b) -> {
            Long aTime = toLong(a.get("created_at"));
            Long bTime = toLong(b.get("created_at"));
            return bTime.compareTo(aTime);
        });
        return sessions;
    }

    /**
     * Gets session state by ID.
     * Returns null if the session does not exist.
     */
    public Map<String, Object> getSession(String agentId, String sessionId) {
        Path sessionFile = sessionsDir.resolve(agentId).resolve(sessionId + ".json");
        if (!Files.exists(sessionFile)) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> session = objectMapper.readValue(sessionFile.toFile(), Map.class);
            return session;
        } catch (IOException e) {
            log.error("Failed to read session: {}/{}", agentId, sessionId, e);
            return null;
        }
    }

    /**
     * Deletes a session.
     */
    public void deleteSession(String agentId, String sessionId) {
        log.info("Deleting session: {}/{}", agentId, sessionId);
        Path sessionFile = sessionsDir.resolve(agentId).resolve(sessionId + ".json");
        try {
            Files.deleteIfExists(sessionFile);
        } catch (IOException e) {
            log.error("Failed to delete session: {}/{}", agentId, sessionId, e);
        }
    }

    /**
     * Creates a new session.
     * Generates a UUID, writes the session data to a JSON file.
     */
    public Map<String, Object> createSession(String agentId, String sessionName) {
        String sessionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("agent_id", agentId);
        session.put("session_id", sessionId);
        session.put("name", sessionName != null ? sessionName : "session-" + now);
        session.put("created_at", now);
        session.put("updated_at", now);
        session.put("messages", new ArrayList<>());

        Path agentDir = sessionsDir.resolve(agentId);
        try {
            Files.createDirectories(agentDir);
            Path sessionFile = agentDir.resolve(sessionId + ".json");
            writeSession(sessionFile, session);
        } catch (IOException e) {
            log.error("Failed to create session file: {}/{}", agentId, sessionId, e);
        }
        log.info("Created session: {}/{}", agentId, sessionId);
        return session;
    }

    /**
     * Renames a session.
     */
    public void renameSession(String agentId, String sessionId, String newName) {
        Path sessionFile = sessionsDir.resolve(agentId).resolve(sessionId + ".json");
        if (!Files.exists(sessionFile)) {
            throw new RuntimeException("Session not found: " + agentId + "/" + sessionId);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> session = objectMapper.readValue(sessionFile.toFile(), Map.class);
            session.put("name", newName);
            session.put("updated_at", System.currentTimeMillis());
            writeSession(sessionFile, session);
            log.info("Renamed session: {}/{} -> {}", agentId, sessionId, newName);
        } catch (IOException e) {
            log.error("Failed to rename session: {}/{}", agentId, sessionId, e);
            throw new RuntimeException("Failed to rename session", e);
        }
    }

    /**
     * Writes session data to a JSON file atomically.
     */
    private void writeSession(Path sessionFile, Map<String, Object> session) throws IOException {
        Path parent = sessionFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempFile = sessionFile.resolveSibling(sessionFile.getFileName() + ".tmp");
        objectMapper.writeValue(tempFile.toFile(), session);
        Files.move(tempFile, sessionFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
