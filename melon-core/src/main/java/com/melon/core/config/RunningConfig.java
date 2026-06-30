/**
 * @author melon
 */
package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 运行参数配置. 对应 Python RunningConfig.
 */
public class RunningConfig {

    @JsonProperty("max_iters")
    private int maxIters = 50;

    @JsonProperty("llm_retry_enabled")
    private boolean llmRetryEnabled = true;

    @JsonProperty("llm_max_retries")
    private int llmMaxRetries = 3;

    @JsonProperty("llm_backoff_base")
    private double llmBackoffBase = 1.0;

    @JsonProperty("llm_backoff_cap")
    private double llmBackoffCap = 60.0;

    @JsonProperty("llm_max_concurrent")
    private int llmMaxConcurrent = 5;

    @JsonProperty("llm_max_qpm")
    private int llmMaxQpm = 0;

    @JsonProperty("task_list_enabled")
    private boolean taskListEnabled = true;

    @JsonProperty("shell_command_timeout")
    private double shellCommandTimeout = 60.0;

    @JsonProperty("shell_command_executable")
    private String shellCommandExecutable;

    @JsonProperty("fallback_model")
    private String fallbackModel;

    public int getMaxIters() { return maxIters; }
    public void setMaxIters(int maxIters) { this.maxIters = maxIters; }

    public boolean isLlmRetryEnabled() { return llmRetryEnabled; }
    public void setLlmRetryEnabled(boolean llmRetryEnabled) { this.llmRetryEnabled = llmRetryEnabled; }

    public int getLlmMaxRetries() { return llmMaxRetries; }
    public void setLlmMaxRetries(int llmMaxRetries) { this.llmMaxRetries = llmMaxRetries; }

    public double getLlmBackoffBase() { return llmBackoffBase; }
    public void setLlmBackoffBase(double llmBackoffBase) { this.llmBackoffBase = llmBackoffBase; }

    public double getLlmBackoffCap() { return llmBackoffCap; }
    public void setLlmBackoffCap(double llmBackoffCap) { this.llmBackoffCap = llmBackoffCap; }

    public int getLlmMaxConcurrent() { return llmMaxConcurrent; }
    public void setLlmMaxConcurrent(int llmMaxConcurrent) { this.llmMaxConcurrent = llmMaxConcurrent; }

    public int getLlmMaxQpm() { return llmMaxQpm; }
    public void setLlmMaxQpm(int llmMaxQpm) { this.llmMaxQpm = llmMaxQpm; }

    public boolean isTaskListEnabled() { return taskListEnabled; }
    public void setTaskListEnabled(boolean taskListEnabled) { this.taskListEnabled = taskListEnabled; }

    public double getShellCommandTimeout() { return shellCommandTimeout; }
    public void setShellCommandTimeout(double shellCommandTimeout) { this.shellCommandTimeout = shellCommandTimeout; }

    public String getShellCommandExecutable() { return shellCommandExecutable; }
    public void setShellCommandExecutable(String shellCommandExecutable) { this.shellCommandExecutable = shellCommandExecutable; }

    public String getFallbackModel() { return fallbackModel; }
    public void setFallbackModel(String fallbackModel) { this.fallbackModel = fallbackModel; }
}
