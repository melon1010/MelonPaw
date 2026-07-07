package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ToolResultPruningConfig {

    @JsonProperty("enabled")
    private boolean enabled = false;

    @JsonProperty("pruning_recent_n")
    private int pruningRecentN = 0;

    @JsonProperty("pruning_old_msg_max_bytes")
    private int pruningOldMsgMaxBytes = 0;

    @JsonProperty("pruning_recent_msg_max_bytes")
    private int pruningRecentMsgMaxBytes = 0;

    @JsonProperty("offload_retention_days")
    private int offloadRetentionDays = 0;

    @JsonProperty("exempt_file_extensions")
    private List<String> exemptFileExtensions = List.of();

    @JsonProperty("exempt_tool_names")
    private List<String> exemptToolNames = List.of("chat_with_agent");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getPruningRecentN() { return pruningRecentN; }
    public void setPruningRecentN(int pruningRecentN) { this.pruningRecentN = pruningRecentN; }

    public int getPruningOldMsgMaxBytes() { return pruningOldMsgMaxBytes; }
    public void setPruningOldMsgMaxBytes(int pruningOldMsgMaxBytes) { this.pruningOldMsgMaxBytes = pruningOldMsgMaxBytes; }

    public int getPruningRecentMsgMaxBytes() { return pruningRecentMsgMaxBytes; }
    public void setPruningRecentMsgMaxBytes(int pruningRecentMsgMaxBytes) { this.pruningRecentMsgMaxBytes = pruningRecentMsgMaxBytes; }

    public int getOffloadRetentionDays() { return offloadRetentionDays; }
    public void setOffloadRetentionDays(int offloadRetentionDays) { this.offloadRetentionDays = offloadRetentionDays; }

    public List<String> getExemptFileExtensions() { return exemptFileExtensions; }
    public void setExemptFileExtensions(List<String> exemptFileExtensions) { this.exemptFileExtensions = exemptFileExtensions; }

    public List<String> getExemptToolNames() { return exemptToolNames; }
    public void setExemptToolNames(List<String> exemptToolNames) { this.exemptToolNames = exemptToolNames; }
}
