/**
 * @author melon
 */
package com.melon.app.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务追踪器. 对应 Python app/runner/task_tracker.py.
 * <p>
 * 追踪进行中的 agent 任务, 支持查询状态和取消任务.
 * 使用 ConcurrentHashMap 保证线程安全.
 */
@Component
public class TaskTracker {

    private static final Logger log = LoggerFactory.getLogger(TaskTracker.class);

    // ======================== Task Status ========================

    public enum TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    // ======================== Task Info ========================

    public static class TaskInfo {
        private final String taskId;
        private final String agentId;
        private final String sessionId;
        private volatile TaskStatus status;
        private final String createdAt;
        private volatile String finishedAt;
        private volatile String errorMessage;
        private volatile Thread workerThread;
        private volatile boolean cancelRequested;

        public TaskInfo(String taskId, String agentId, String sessionId) {
            this.taskId = taskId;
            this.agentId = agentId;
            this.sessionId = sessionId;
            this.status = TaskStatus.PENDING;
            this.createdAt = Instant.now().toString();
        }

        public String getTaskId() { return taskId; }
        public String getAgentId() { return agentId; }
        public String getSessionId() { return sessionId; }
        public TaskStatus getStatus() { return status; }
        public String getCreatedAt() { return createdAt; }
        public String getFinishedAt() { return finishedAt; }
        public String getErrorMessage() { return errorMessage; }
        public boolean isCancelRequested() { return cancelRequested; }

        void setStatus(TaskStatus status) { this.status = status; }
        void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
        void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        void setWorkerThread(Thread thread) { this.workerThread = thread; }
        void setCancelRequested(boolean cancelRequested) { this.cancelRequested = cancelRequested; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("task_id", taskId);
            m.put("agent_id", agentId);
            m.put("session_id", sessionId);
            m.put("status", status != null ? status.name().toLowerCase() : "unknown");
            m.put("created_at", createdAt);
            m.put("finished_at", finishedAt);
            m.put("error", errorMessage);
            m.put("cancel_requested", cancelRequested);
            return m;
        }
    }

    // ======================== Fields ========================

    private final Map<String, TaskInfo> tasks = new ConcurrentHashMap<>();

    // ======================== Public API ========================

    /**
     * 注册新任务, 返回 taskId.
     */
    public String register(String agentId, String sessionId) {
        String taskId = UUID.randomUUID().toString();
        TaskInfo info = new TaskInfo(taskId, agentId, sessionId);
        info.setWorkerThread(Thread.currentThread());
        tasks.put(taskId, info);
        log.info("Task registered: id={}, agent={}, session={}", taskId, agentId, sessionId);
        return taskId;
    }

    /**
     * 标记任务为运行中.
     */
    public void markRunning(String taskId) {
        TaskInfo info = tasks.get(taskId);
        if (info != null) {
            info.setStatus(TaskStatus.RUNNING);
            log.debug("Task marked running: id={}", taskId);
        }
    }

    /**
     * 标记任务为已完成.
     */
    public void markCompleted(String taskId) {
        TaskInfo info = tasks.get(taskId);
        if (info != null) {
            info.setStatus(TaskStatus.COMPLETED);
            info.setFinishedAt(Instant.now().toString());
            log.info("Task completed: id={}", taskId);
        }
    }

    /**
     * 标记任务为失败.
     */
    public void markFailed(String taskId, String errorMessage) {
        TaskInfo info = tasks.get(taskId);
        if (info != null) {
            info.setStatus(TaskStatus.FAILED);
            info.setFinishedAt(Instant.now().toString());
            info.setErrorMessage(errorMessage);
            log.warn("Task failed: id={}, error={}", taskId, errorMessage);
        }
    }

    /**
     * 请求取消任务. 设置取消标志并中断工作线程.
     */
    public boolean cancel(String taskId) {
        TaskInfo info = tasks.get(taskId);
        if (info == null) {
            log.warn("Cannot cancel: task not found: id={}", taskId);
            return false;
        }
        info.setCancelRequested(true);
        Thread thread = info.workerThread;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            log.info("Task cancel requested and thread interrupted: id={}", taskId);
        } else {
            log.info("Task cancel requested (thread not alive): id={}", taskId);
        }
        info.setStatus(TaskStatus.CANCELLED);
        info.setFinishedAt(Instant.now().toString());
        return true;
    }

    /**
     * 检查任务是否被请求取消.
     */
    public boolean isCancelRequested(String taskId) {
        TaskInfo info = tasks.get(taskId);
        return info != null && info.isCancelRequested();
    }

    /**
     * 获取任务信息.
     */
    public TaskInfo get(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 列出所有任务.
     */
    public List<TaskInfo> listAll() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * 列出指定 Agent 的任务.
     */
    public List<TaskInfo> listByAgent(String agentId) {
        List<TaskInfo> result = new ArrayList<>();
        for (TaskInfo info : tasks.values()) {
            if (agentId.equals(info.getAgentId())) {
                result.add(info);
            }
        }
        return result;
    }

    /**
     * 清理已完成/已取消/已失败的任务.
     */
    public int cleanup() {
        int removed = 0;
        for (String taskId : new ArrayList<>(tasks.keySet())) {
            TaskInfo info = tasks.get(taskId);
            if (info != null) {
                TaskStatus status = info.getStatus();
                if (status == TaskStatus.COMPLETED
                        || status == TaskStatus.CANCELLED
                        || status == TaskStatus.FAILED) {
                    tasks.remove(taskId);
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("Cleaned up {} finished tasks", removed);
        }
        return removed;
    }
}
