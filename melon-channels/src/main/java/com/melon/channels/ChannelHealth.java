package com.melon.channels;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class ChannelHealth {

    private String channel;
    private String status;
    private boolean running;
    private boolean available;
    private boolean implemented;
    private String detail;
    private String checkedAt = Instant.now().toString();
    private Map<String, Object> meta = new LinkedHashMap<>();

    public static ChannelHealth of(String channel, String status, boolean running,
                                   boolean available, boolean implemented, String detail) {
        ChannelHealth health = new ChannelHealth();
        health.setChannel(channel);
        health.setStatus(status);
        health.setRunning(running);
        health.setAvailable(available);
        health.setImplemented(implemented);
        health.setDetail(detail);
        return health;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channel", channel);
        result.put("status", status);
        result.put("running", running);
        result.put("available", available);
        result.put("implemented", implemented);
        result.put("detail", detail);
        result.put("checked_at", checkedAt);
        result.put("meta", meta);
        return result;
    }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }

    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }

    public boolean isImplemented() { return implemented; }
    public void setImplemented(boolean implemented) { this.implemented = implemented; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getCheckedAt() { return checkedAt; }
    public void setCheckedAt(String checkedAt) { this.checkedAt = checkedAt; }

    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> meta) { this.meta = meta != null ? new LinkedHashMap<>(meta) : new LinkedHashMap<>(); }
}
