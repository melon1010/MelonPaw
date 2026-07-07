package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ScrollContextConfig {

    @JsonProperty("db_filename")
    private String dbFilename = "history.db";

    @JsonProperty("tool_output_token_cap")
    private int toolOutputTokenCap = 3000;

    @JsonProperty("pinned")
    private int pinned = 1;

    @JsonProperty("repl_timeout_s")
    private int replTimeoutS = 300;

    @JsonProperty("history_retention_days")
    private int historyRetentionDays = 30;

    @JsonProperty("allow_unsandboxed")
    private boolean allowUnsandboxed = false;

    @JsonProperty("offload_dialog")
    private boolean offloadDialog = false;

    public String getDbFilename() { return dbFilename; }
    public void setDbFilename(String dbFilename) { this.dbFilename = dbFilename; }

    public int getToolOutputTokenCap() { return toolOutputTokenCap; }
    public void setToolOutputTokenCap(int toolOutputTokenCap) { this.toolOutputTokenCap = toolOutputTokenCap; }

    public int getPinned() { return pinned; }
    public void setPinned(int pinned) { this.pinned = pinned; }

    public int getReplTimeoutS() { return replTimeoutS; }
    public void setReplTimeoutS(int replTimeoutS) { this.replTimeoutS = replTimeoutS; }

    public int getHistoryRetentionDays() { return historyRetentionDays; }
    public void setHistoryRetentionDays(int historyRetentionDays) { this.historyRetentionDays = historyRetentionDays; }

    public boolean isAllowUnsandboxed() { return allowUnsandboxed; }
    public void setAllowUnsandboxed(boolean allowUnsandboxed) { this.allowUnsandboxed = allowUnsandboxed; }

    public boolean isOffloadDialog() { return offloadDialog; }
    public void setOffloadDialog(boolean offloadDialog) { this.offloadDialog = offloadDialog; }
}
