/**
 * @author melon
 */
package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 上下文压缩配置. 对应 Python ContextCompactConfig.
 */
public class ContextCompactConfig {

    @JsonProperty("enabled")
    private boolean enabled = true;

    @JsonProperty("compact_threshold_ratio")
    private double compactThresholdRatio = 0.8;

    @JsonProperty("reserve_threshold_ratio")
    private double reserveThresholdRatio = 0.1;

    @JsonProperty("compact_with_thinking_block")
    private boolean compactWithThinkingBlock = true;

    @JsonProperty("trigger_messages")
    private int triggerMessages = 50;

    @JsonProperty("keep_messages")
    private int keepMessages = 20;

    @JsonProperty("summary_model")
    private String summaryModel;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getCompactThresholdRatio() { return compactThresholdRatio; }
    public void setCompactThresholdRatio(double compactThresholdRatio) { this.compactThresholdRatio = compactThresholdRatio; }

    public double getReserveThresholdRatio() { return reserveThresholdRatio; }
    public void setReserveThresholdRatio(double reserveThresholdRatio) { this.reserveThresholdRatio = reserveThresholdRatio; }

    public boolean isCompactWithThinkingBlock() { return compactWithThinkingBlock; }
    public void setCompactWithThinkingBlock(boolean compactWithThinkingBlock) { this.compactWithThinkingBlock = compactWithThinkingBlock; }

    public int getTriggerMessages() { return triggerMessages; }
    public void setTriggerMessages(int triggerMessages) { this.triggerMessages = triggerMessages; }

    public int getKeepMessages() { return keepMessages; }
    public void setKeepMessages(int keepMessages) { this.keepMessages = keepMessages; }

    public String getSummaryModel() { return summaryModel; }
    public void setSummaryModel(String summaryModel) { this.summaryModel = summaryModel; }
}
