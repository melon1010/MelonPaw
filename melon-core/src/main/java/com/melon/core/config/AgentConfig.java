package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * 单 Agent 配置. 对应 Python config.py 的 AgentConfig.
 */
public class AgentConfig {

    @JsonProperty("name")
    private String name = "default";

    @JsonProperty("description")
    private String description = "";

    @JsonProperty("enabled")
    private boolean enabled = true;

    @JsonProperty("active_model")
    private String activeModel = "";

    @JsonProperty("workspace_dir")
    private String workspaceDir;

    @JsonProperty("system_prompt_files")
    private List<String> systemPromptFiles = List.of("AGENTS.md", "SOUL.md", "PROFILE.md");

    @JsonProperty("skills")
    private List<String> skills = List.of();

    @JsonProperty("running")
    private RunningConfig running = new RunningConfig();

    @JsonProperty("tools")
    private ToolsConfig tools = new ToolsConfig();

    @JsonProperty("context_compact")
    private ContextCompactConfig contextCompact = new ContextCompactConfig();

    @JsonProperty("coding_mode")
    private CodingModeConfig codingMode = new CodingModeConfig();

    @JsonProperty("plan_mode")
    private PlanModeConfig planMode = new PlanModeConfig();

    @JsonProperty("approval")
    private ApprovalConfig approval = new ApprovalConfig();

    @JsonProperty("heartbeat")
    private HeartbeatConfig heartbeat = new HeartbeatConfig();

    @JsonProperty("language")
    private String language = "zh";

    @JsonProperty("timezone")
    private String timezone = "UTC";

    @JsonProperty("frontend_running_config")
    private Map<String, Object> frontendRunningConfig = Map.of();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getActiveModel() { return activeModel; }
    public void setActiveModel(String activeModel) { this.activeModel = activeModel; }

    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }

    public List<String> getSystemPromptFiles() { return systemPromptFiles; }
    public void setSystemPromptFiles(List<String> systemPromptFiles) { this.systemPromptFiles = systemPromptFiles; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }

    public RunningConfig getRunning() { return running; }
    public void setRunning(RunningConfig running) { this.running = running; }

    public ToolsConfig getTools() { return tools; }
    public void setTools(ToolsConfig tools) { this.tools = tools; }

    public ContextCompactConfig getContextCompact() { return contextCompact; }
    public void setContextCompact(ContextCompactConfig contextCompact) { this.contextCompact = contextCompact; }

    public CodingModeConfig getCodingMode() { return codingMode; }
    public void setCodingMode(CodingModeConfig codingMode) { this.codingMode = codingMode; }

    public PlanModeConfig getPlanMode() { return planMode; }
    public void setPlanMode(PlanModeConfig planMode) { this.planMode = planMode; }

    public ApprovalConfig getApproval() { return approval; }
    public void setApproval(ApprovalConfig approval) { this.approval = approval; }

    public HeartbeatConfig getHeartbeat() { return heartbeat; }
    public void setHeartbeat(HeartbeatConfig heartbeat) { this.heartbeat = heartbeat; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public Map<String, Object> getFrontendRunningConfig() { return frontendRunningConfig; }
    public void setFrontendRunningConfig(Map<String, Object> frontendRunningConfig) { this.frontendRunningConfig = frontendRunningConfig; }

    public static class ApprovalConfig {
        @JsonProperty("level")
        private String level = "AUTO"; // OFF / AUTO / SMART / STRICT

        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
    }
}
