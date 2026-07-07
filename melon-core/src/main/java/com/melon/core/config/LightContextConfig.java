package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LightContextConfig {

    @JsonProperty("strategy")
    private String strategy = "scroll";

    @JsonProperty("dialog_path")
    private String dialogPath = "dialog";

    @JsonProperty("token_count_estimate_divisor")
    private double tokenCountEstimateDivisor = 4.0;

    @JsonProperty("context_compact_config")
    private ContextCompactConfig contextCompactConfig = new ContextCompactConfig();

    @JsonProperty("tool_result_pruning_config")
    private ToolResultPruningConfig toolResultPruningConfig = new ToolResultPruningConfig();

    @JsonProperty("scroll_config")
    private ScrollContextConfig scrollConfig = new ScrollContextConfig();

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public String getDialogPath() { return dialogPath; }
    public void setDialogPath(String dialogPath) { this.dialogPath = dialogPath; }

    public double getTokenCountEstimateDivisor() { return tokenCountEstimateDivisor; }
    public void setTokenCountEstimateDivisor(double tokenCountEstimateDivisor) { this.tokenCountEstimateDivisor = tokenCountEstimateDivisor; }

    public ContextCompactConfig getContextCompactConfig() { return contextCompactConfig; }
    public void setContextCompactConfig(ContextCompactConfig contextCompactConfig) { this.contextCompactConfig = contextCompactConfig; }

    public ToolResultPruningConfig getToolResultPruningConfig() { return toolResultPruningConfig; }
    public void setToolResultPruningConfig(ToolResultPruningConfig toolResultPruningConfig) { this.toolResultPruningConfig = toolResultPruningConfig; }

    public ScrollContextConfig getScrollConfig() { return scrollConfig; }
    public void setScrollConfig(ScrollContextConfig scrollConfig) { this.scrollConfig = scrollConfig; }
}
