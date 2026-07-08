---
name: cron
description: 仅在需要未来定时执行或周期执行任务时使用。Java 版通过 create_cron_job 工具创建任务，前端/API 管理当前 workspace 的 jobs.json；不要使用 melonpaw cron CLI。
metadata:
  builtin_skill_version: "2.0-java"
  qwenpaw:
    emoji: "⏰"
---

# 定时任务管理

本 skill 适用于 Java melonPaw 后端。定时任务属于当前 agent workspace，落盘到:

```text
<workspace>/jobs.json
```

前端定时任务页面读取同一份数据，接口为 `/api/cron/jobs`，请求会通过 `X-Agent-Id` 选择当前 workspace。

## 什么时候用

只有在需要未来自动执行或周期重复执行时使用。

应该使用:
- 用户要求每天、每周、每小时执行某事
- 用户要求明天 9 点、下周一、某个未来时间执行
- 需要长期周期性通知、检查、汇报

不应使用:
- 只是现在立即执行一次
- 只是当前会话内正常回复
- 用户没有明确时间、周期或任务内容

## 创建任务

在对话中创建任务时，优先调用 Java 内置工具:

```text
create_cron_job
```

工具参数:

```json
{
  "name": "每日天气",
  "cron": "0 9 * * *",
  "prompt": "查询南京今日天气并给出穿衣建议",
  "timezone": "Asia/Shanghai",
  "enabled": true
}
```

字段规则:
- `name`: 任务名称，用户可识别
- `cron`: 5 段 cron 表达式，例如 `0 9 * * *`
- `prompt`: 到点后要让 agent 执行的任务内容
- `timezone`: 默认 `Asia/Shanghai`
- `enabled`: 默认 `true`

创建成功后，任务会写入当前 workspace 的 `jobs.json`，前端定时任务列表应立即能看到。

## 管理任务

不要调用 `melonpaw cron` 命令。Java 项目不依赖 Python CLI。

管理入口:
- 前端定时任务页面
- Java API `/api/cron/jobs`
- 兼容旧 API `/api/crons`

常用 API 语义:
- `GET /api/cron/jobs`: 列出当前 agent 的任务
- `POST /api/cron/jobs`: 创建任务
- `PUT /api/cron/jobs/{id}`: 替换任务
- `DELETE /api/cron/jobs/{id}`: 删除任务
- `POST /api/cron/jobs/{id}/pause`: 暂停任务
- `POST /api/cron/jobs/{id}/resume`: 恢复任务
- `GET /api/cron/jobs/{id}/state`: 查看任务状态

## 创建前确认

缺少以下信息时，先追问用户，不要猜:
- 任务名称
- 执行时间或周期
- 任务内容

如果用户说“每天 9 点”，可直接转成:

```text
0 9 * * *
```

如果用户说“每 2 小时”，可直接转成:

```text
0 */2 * * *
```

如果用户说“一次性提醒”，当前 Java 工具还没有一次性 `run_at` 支持，应说明目前只支持 cron 周期任务，并询问是否改为周期任务，或让用户在前端/API 后续补充。

## Cron 示例

```text
0 9 * * *      每天 9:00
0 */2 * * *    每 2 小时
30 8 * * 1-5   工作日 8:30
0 0 * * 0      每周日 0:00
*/15 * * * *   每 15 分钟
```

## 行为要求

- 不要使用 `melonpaw cron ...`。
- 不要直接编辑 `jobs.json`，除非工具/API 不可用且用户明确要求。
- 创建任务后，告诉用户任务名、cron 表达式和当前 workspace。
- 如果只是立即执行一次，直接执行，不创建定时任务。
- 高频任务要谨慎，避免创建过多每分钟级别的任务。
