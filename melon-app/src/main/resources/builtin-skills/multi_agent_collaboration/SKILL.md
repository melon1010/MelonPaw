---
name: multi_agent_collaboration
description: 当需要其他 agent 的专长、上下文或协作支持，或用户明确要求调用其他 agent 时，使用本 skill。先用 list_agents 查询可用 agents，再用 chat_with_agent 或 submit_to_agent 进行协作。
metadata:
  builtin_skill_version: "1.6"
  qwenpaw:
    emoji: "🤝"
---

# 多智能体协作

## 什么时候用

当你**需要其他 agent 的专业能力、上下文、workspace 内容或协作支持**时，使用本 skill。
如果**用户明确要求某个 agent 参与/协助/回答**，也应使用本 skill。

### 应该使用
- 当前任务明显更适合某个专用 agent
- 需要另一个 agent 的 workspace / 文件 / 上下文
- 需要第二意见或专业复核
- 用户明确要求某个 agent 参与或调用其他 agent
- 任务可以拆给同项目的临时子 agent 独立处理

### 不应使用
- 你自己可以直接完成，且用户没有明确要求调用其他 agent
- 只是普通问答，不需要专门 agent
- 信息不足，应先追问用户
- 刚收到 Agent B 的消息，**不要再调用 Agent B**，避免循环

## 决策规则

1. **如果用户明确要求调用其他 agent，优先按要求执行**
2. **否则，能自己做，就不要调用**
3. **调用前先查 agent，不要猜 ID**
4. **需要上下文续聊时，必须传 `session_id`**
5. **不要回调消息来源 agent**
6. **简单快速咨询用 `chat_with_agent`；复杂或耗时任务用 `submit_to_agent`，再用 `check_agent_task` 查询**

---

## 可用工具

### 1) 查询可用 agents

```text
list_agents()
```

使用返回结果里的 agent `id`，不要猜测 ID。

### 2) 发起实时对话

```text
chat_with_agent(
  to_agent="<target_agent_id>",
  text="[Agent <your_agent_id> requesting] ...",
)
```

实时模式会等待目标 agent 完成，并返回回复。返回中通常包含：

```text
[SESSION: ...]
```

后续需要保留上下文时，复制该 session_id 继续传入。

### 3) 继续已有对话

```text
chat_with_agent(
  to_agent="<target_agent_id>",
  session_id="<previous_session_id>",
  text="[Agent <your_agent_id> requesting] 请基于刚才的结论继续展开。",
)
```

**重点**:
- 不传 `session_id` = 新对话
- 传 `session_id` = 续聊，保留目标 agent 的上下文

### 4) 提交后台任务

复杂任务包括：数据分析、报告生成、批量处理、外部 API 调用、长时间代码检查等。

```text
submit_to_agent(
  to_agent="<target_agent_id>",
  text="[Agent <your_agent_id> requesting] ...",
)
```

返回中通常包含：

```text
[TASK_ID: ...]
[SESSION: ...]
```

提交后不要硬等。根据任务复杂度，等待合理时间后再查：
- 简单分析：10-20 秒
- 复杂分析：30-60 秒
- 批量处理：1-3 分钟

### 5) 查询后台任务

```text
check_agent_task(
  task_id="<task_id>",
)
```

任务完成时会返回目标 agent 的最终结果；未完成时会返回当前状态。

### 6) 当前项目内派发临时子任务

如果任务适合在**当前 agent / 当前 workspace** 下开一个干净的临时子任务，而不是调用另一个长期配置的 agent，可使用：

```text
spawn_subagent(
  task="请独立检查当前项目中的测试风险并给出结论。",
)
```

如需后台执行：

```text
spawn_subagent(
  task="请独立完成较长的代码审查并输出报告。",
  background=true,
)
```

返回 task_id 后同样用 `check_agent_task(task_id=...)` 查询。

当前 Java 运行时尚未实现 `spawn_subagent(fork=true)` 的 worktree 隔离；不要使用 `fork=true`，除非工具明确返回已支持。

---

## 任务模式选择

| 任务类型 | 使用方式 |
|---------|---------|
| 简单快速查询 | `chat_with_agent` |
| 需要多轮咨询 | `chat_with_agent` + `session_id` |
| 复杂或耗时任务 | `submit_to_agent` + `check_agent_task` |
| 当前项目内独立子任务 | `spawn_subagent` |

---

## 最小工作流

### 实时模式

```text
1. 判断是否需要其他 agent，或用户是否明确要求调用
2. list_agents()
3. chat_with_agent(...) 发起对话
4. 从输出中记录 [SESSION: ...]
5. 后续需要上下文时传 session_id
```

### 后台模式

```text
1. 判断任务是否复杂或耗时
2. list_agents()
3. submit_to_agent(...) 提交任务
4. 从输出中记录 [TASK_ID: ...] 和 [SESSION: ...]
5. 继续处理其他工作
6. 等待合理时间后 check_agent_task(...)
```

---

## 身份前缀

消息建议以以下内容开头：

```text
[Agent <your_agent_id> requesting] ...
```

这样目标 agent 能明确消息来源。若系统已自动添加，不要重复添加。

---

## 简短示例

### 用户明确要求调用其他 agent

```text
list_agents()

chat_with_agent(
  to_agent="finance_bot",
  text="[Agent scheduler_bot requesting] User explicitly asked to consult finance_bot. 请回答当前待处理的财务任务。",
)
```

### 新对话

```text
chat_with_agent(
  to_agent="finance_bot",
  text="[Agent scheduler_bot requesting] 今天有哪些待处理的财务任务？",
)
```

### 续聊

```text
chat_with_agent(
  to_agent="finance_bot",
  session_id="scheduler_bot:to:finance_bot:1710912345:a1b2c3d4",
  text="[Agent scheduler_bot requesting] 展开第 2 项。",
)
```

### 后台任务

```text
submit_to_agent(
  to_agent="qa_bot",
  text="[Agent default requesting] 请检查这次改动的回归风险并给出报告。",
)

check_agent_task(
  task_id="task-abc123",
)
```

---

## 常见错误

### 错误 1：没先查 agent

不要猜 agent ID，先调用：

```text
list_agents()
```

### 错误 2：想续聊但没传 session_id

这会创建新对话，目标 agent 可能丢失上下文。

### 错误 3：回调来源 agent

如果你刚收到 Agent B 的消息，不要再调用 Agent B。

### 错误 4：耗时任务用实时模式

长任务应使用 `submit_to_agent` 或 `spawn_subagent(background=true)`，不要长时间阻塞当前对话。
