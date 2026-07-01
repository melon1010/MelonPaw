package com.melon.core.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan 状态数据模型. 对应 Python plan/state.py.
 * <p>
 * 描述 Agent 在 Plan 模式下的执行计划, 包含步骤列表和状态流转.
 */
public class PlanState {

    /**
     * Plan 整体状态.
     */
    public enum Status {
        /** 草稿: Agent 正在生成计划 */
        DRAFT,
        /** 待确认: 计划已生成, 等待用户确认 */
        PENDING,
        /** 已确认: 用户已批准, 开始执行 */
        CONFIRMED,
        /** 已拒绝: 用户拒绝, Agent 需修改 */
        REJECTED,
        /** 执行中: 正在执行计划步骤 */
        EXECUTING,
        /** 已完成: 所有步骤完成 */
        COMPLETED,
        /** 已取消 */
        CANCELLED
    }

    private String sessionId;
    private String agentId;
    private String title;
    private String summary;
    private List<PlanStep> steps = new ArrayList<>();
    private int currentStepIndex = 0;
    private Status status = Status.DRAFT;
    private String rejectReason;
    private long createdAt;
    private long updatedAt;

    public PlanState() {
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public PlanState(String sessionId, String agentId) {
        this();
        this.sessionId = sessionId;
        this.agentId = agentId;
    }

    // ======================== Business Logic ========================

    /**
     * 添加步骤.
     */
    public void addStep(String description) {
        steps.add(new PlanStep("step-" + (steps.size() + 1), description));
        touch();
    }

    /**
     * 标记为待确认.
     */
    public void markPending() {
        this.status = Status.PENDING;
        touch();
    }

    /**
     * 确认计划, 开始执行.
     */
    public void confirm() {
        this.status = Status.CONFIRMED;
        this.rejectReason = null;
        if (!steps.isEmpty()) {
            steps.get(0).setStatus(PlanStep.StepStatus.IN_PROGRESS);
        }
        touch();
    }

    /**
     * 拒绝计划.
     */
    public void reject(String reason) {
        this.status = Status.REJECTED;
        this.rejectReason = reason;
        touch();
    }

    /**
     * 开始执行.
     */
    public void startExecuting() {
        this.status = Status.EXECUTING;
        touch();
    }

    /**
     * 完成当前步骤, 前进到下一步.
     */
    public void completeCurrentStep(String result) {
        if (currentStepIndex < steps.size()) {
            steps.get(currentStepIndex).complete(result);
            currentStepIndex++;
            if (currentStepIndex < steps.size()) {
                steps.get(currentStepIndex).setStatus(PlanStep.StepStatus.IN_PROGRESS);
            } else {
                this.status = Status.COMPLETED;
            }
            touch();
        }
    }

    /**
     * 标记当前步骤失败.
     */
    public void failCurrentStep(String error) {
        if (currentStepIndex < steps.size()) {
            steps.get(currentStepIndex).fail(error);
            touch();
        }
    }

    /**
     * 取消计划.
     */
    public void cancel() {
        this.status = Status.CANCELLED;
        touch();
    }

    /**
     * 是否已完成.
     */
    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    /**
     * 是否可执行 (已确认或执行中).
     */
    public boolean isExecutable() {
        return status == Status.CONFIRMED || status == Status.EXECUTING;
    }

    private void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    // ======================== Serialization ========================

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", sessionId);
        m.put("agent_id", agentId);
        m.put("title", title);
        m.put("summary", summary);
        m.put("status", status);
        m.put("current_step_index", currentStepIndex);
        m.put("reject_reason", rejectReason);
        m.put("created_at", createdAt);
        m.put("updated_at", updatedAt);
        m.put("steps", steps.stream().map(PlanStep::toMap).toList());
        return m;
    }

    // ======================== Getters/Setters ========================

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<PlanStep> getSteps() { return steps; }
    public void setSteps(List<PlanStep> steps) { this.steps = steps; }

    public int getCurrentStepIndex() { return currentStepIndex; }
    public void setCurrentStepIndex(int currentStepIndex) { this.currentStepIndex = currentStepIndex; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    // ======================== Plan Step ========================

    /**
     * 计划步骤.
     */
    public static class PlanStep {
        /**
         * 步骤状态.
         */
        public enum StepStatus {
            PENDING,
            IN_PROGRESS,
            COMPLETED,
            FAILED,
            SKIPPED
        }

        private String id;
        private String description;
        private StepStatus status = StepStatus.PENDING;
        private String result;
        private String error;

        public PlanStep() {}

        public PlanStep(String id, String description) {
            this.id = id;
            this.description = description;
        }

        public void complete(String result) {
            this.status = StepStatus.COMPLETED;
            this.result = result;
        }

        public void fail(String error) {
            this.status = StepStatus.FAILED;
            this.error = error;
        }

        public void skip() {
            this.status = StepStatus.SKIPPED;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("description", description);
            m.put("status", status);
            m.put("result", result);
            m.put("error", error);
            return m;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public StepStatus getStatus() { return status; }
        public void setStatus(StepStatus status) { this.status = status; }

        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}
