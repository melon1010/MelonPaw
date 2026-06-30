/**
 * @author melon
 */
package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Approval level configuration for tool execution.
 *
 * <p>Defines how the agent handles tool execution approval:
 * <ul>
 *   <li>{@code OFF} - No approval required, all tools auto-approved.</li>
 *   <li>{@code AUTO} - Automatic approval based on tool safety classification.</li>
 *   <li>{@code SMART} - Smart approval: auto-approve safe tools, require approval for risky ones.</li>
 *   <li>{@code STRICT} - Require explicit approval for every tool call.</li>
 * </ul>
 *
 * <p>This config is consumed by {@link com.melon.core.middleware.ToolGuardMiddleware}.
 */
public class ApprovalConfig {

    /**
     * Default approval level.
     */
    public static final String LEVEL_OFF = "OFF";
    public static final String LEVEL_AUTO = "AUTO";
    public static final String LEVEL_SMART = "SMART";
    public static final String LEVEL_STRICT = "STRICT";

    @JsonProperty("level")
    private String level = LEVEL_AUTO;

    // ---- Getters / Setters ----

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }
}
