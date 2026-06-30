/**
 * @author melon
 */
package com.melon.app.backup;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Backup metadata. Stores information about a backup file.
 * Corresponds to Python backup_meta.py.
 */
public class BackupMeta {

    /** Epoch milliseconds when the backup was created */
    private long timestamp;

    /** Melon version at the time of backup */
    private String version;

    /** Backup file size in bytes */
    private long size;

    /** Number of agents included in the backup */
    private int agentCount;

    /** Number of sessions included in the backup */
    private int sessionCount;

    public BackupMeta() {
    }

    public BackupMeta(long timestamp, String version, long size, int agentCount, int sessionCount) {
        this.timestamp = timestamp;
        this.version = version;
        this.size = size;
        this.agentCount = agentCount;
        this.sessionCount = sessionCount;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public int getAgentCount() {
        return agentCount;
    }

    public void setAgentCount(int agentCount) {
        this.agentCount = agentCount;
    }

    public int getSessionCount() {
        return sessionCount;
    }

    public void setSessionCount(int sessionCount) {
        this.sessionCount = sessionCount;
    }

    /**
     * Converts this metadata to a Map for JSON serialization.
     *
     * @return a Map containing all metadata fields
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", timestamp);
        map.put("version", version);
        map.put("size", size);
        map.put("agentCount", agentCount);
        map.put("sessionCount", sessionCount);
        return map;
    }

    /**
     * Creates a BackupMeta from a Map (deserialized JSON).
     *
     * @param map the map containing metadata fields
     * @return a new BackupMeta instance, or null if map is null
     */
    @SuppressWarnings("unchecked")
    public static BackupMeta fromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        BackupMeta meta = new BackupMeta();
        meta.timestamp = toLong(map.get("timestamp"));
        meta.version = map.get("version") != null ? map.get("version").toString() : "unknown";
        meta.size = toLong(map.get("size"));
        meta.agentCount = (int) toLong(map.get("agentCount"));
        meta.sessionCount = (int) toLong(map.get("sessionCount"));
        return meta;
    }

    /**
     * Safely converts a Number or String to long.
     */
    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public String toString() {
        return "BackupMeta{timestamp=" + timestamp + ", version='" + version + '\''
                + ", size=" + size + ", agentCount=" + agentCount
                + ", sessionCount=" + sessionCount + '}';
    }
}
