package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Per-agent heartbeat config used by the QwenPaw console.
 */
public class HeartbeatConfig {

    @JsonProperty("enabled")
    private boolean enabled = false;

    @JsonProperty("every")
    private String every = "6h";

    @JsonProperty("target")
    private String target = "main";

    @JsonProperty("timeoutSeconds")
    private int timeoutSeconds = 300;

    @JsonProperty("activeHours")
    private Map<String, String> activeHours;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getEvery() { return every; }
    public void setEvery(String every) { this.every = every; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public Map<String, String> getActiveHours() { return activeHours; }
    public void setActiveHours(Map<String, String> activeHours) { this.activeHours = activeHours; }
}
