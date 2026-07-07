# QwenPaw Java 项目上下文记忆

## 项目定位

本项目是对 `/Users/melon/IdeaWorkSpace/Github/QwenPaw` 中 Python 侧能力的 Java/Spring Boot 复刻，目标是使用 AgentScope Java 2.0 实现一个可被现有 QwenPaw 前端直接连接的后端。

核心目标不是重新设计一个新产品，而是让现有 QwenPaw 前端在 `VITE_API_BASE_URL` 指向 Java 服务后，主要产品流程真实可用：

- 登录和主界面可进入，不因缺失 API 大面积 404。
- Chat 对话、SSE 流式输出、工具调用、审批、会话持久化可用。
- Agent、Models、Tools、Skills、MCP、Workspace、Coding、Cron、Backup、Token Usage、Settings、Channels 等页面按 Python 前端协议工作。
- 未实现或被明确排除的能力应返回稳定的 disabled/empty 响应，而不是让前端报错。

## 明确约束

- 前端原则上不改。用户多次明确要求：Java 后端兼容现有 QwenPaw 前端，不能靠改前端规避问题。
- Java 侧不能机械照抄 Python。需要参考 Python 的协议、持久化模型、接口字段和产品语义，但实现应优先复用 AgentScope Java 2.0、Spring Boot/WebFlux、现有项目工具类和标准库。
- 代码风格遵循 `ponytail`：最小可行、少造轮子、少引入依赖、避免大重构。
- 渠道系统后来已被用户要求完整接入；CLI/TUI/桌面打包仍不是目标。
- 现有用户/其他 agent 的改动不能随意回滚。

## 接手开发规则

新窗口接手时，先读本文件，再按具体问题读取 Java 代码和 Python QwenPaw 对应实现。不要凭印象修改。

处理问题的顺序：

1. 在 `console/src/api/modules`、相关页面或浏览器请求里确认前端实际调用的路径、方法和字段。
2. 在 `/Users/melon/IdeaWorkSpace/Github/QwenPaw/src/qwenpaw` 找 Python 对应 router/service/runtime 逻辑，确认协议和持久化语义。
3. 在 Java 侧找现有 Controller/Service/Manager/Tool 是否已有可复用实现。
4. 优先修 Java 兼容层和核心服务，不改前端。
5. 改动要小，避免新建并行体系；已有模块能承载就不要新增模块。
6. 改完至少跑 `git diff --check`，涉及 Java 编译的改动跑 `mvn -q -DskipTests test-compile`，较大改动再跑 `mvn -q -DskipTests package`。

## 代码结构导览

主要 Java 模块：

- `melon-app`：Spring Boot 应用、Controller、应用层 service、兼容前端 API。
- `melon-core`：Agent 配置、workspace、AgentScope Harness 构建、provider、middleware、prompt、基础工具类。
- `melon-tools`：内置工具实现，例如 shell、file IO、browser、media、cron、agent 协作、token usage。
- `melon-channels`：渠道配置、adapter、入站队列、出站渲染、访问控制。
- `melon-coding-mode`：LSP/AST 相关工具实现。
- `console`：从 QwenPaw 同步来的前端。除非用户明确要求，不要为了修后端问题改这里。
- `doc`：项目分析和实施计划。

常用入口文件：

- `melon-app/src/main/java/com/melon/app/controller/ConsoleCompatController.java`：`/api/console/chat`、上传、控制台对话入口。
- `melon-app/src/main/java/com/melon/app/controller/ChatsCompatController.java`：前端 chats API。
- `melon-app/src/main/java/com/melon/app/controller/ModelsCompatController.java`：前端 models API。
- `melon-app/src/main/java/com/melon/app/controller/ToolController.java`：工具列表、开关、配置。
- `melon-app/src/main/java/com/melon/app/controller/SkillController.java`：技能池、技能上传、启停、导入。
- `melon-app/src/main/java/com/melon/app/controller/ChannelCompatController.java`：渠道配置、启停、二维码、webhook。
- `melon-app/src/main/java/com/melon/app/controller/CronCompatController.java`：定时任务 API。
- `melon-app/src/main/java/com/melon/app/controller/CodingProjectCompatController.java`：Coding 项目目录 API。
- `melon-app/src/main/java/com/melon/app/controller/FrontendCompatController.java`：若干前端兼容配置接口。
- `melon-core/src/main/java/com/melon/core/agent/MelonAgentFactory.java`：AgentScope HarnessAgent 构建、toolkit、middleware、权限配置。
- `melon-core/src/main/java/com/melon/core/agent/MultiAgentManager.java`：agent 生命周期、reload、workspace 初始化。
- `melon-core/src/main/java/com/melon/core/agent/WorkspaceManager.java`：workspace 目录和默认文件。
- `melon-core/src/main/java/com/melon/core/config/ConfigManager.java`：全局配置、workspace agent config 读写。
- `melon-core/src/main/java/com/melon/core/provider/ProviderManager.java`：模型 provider、active model、OpenAI-compatible 注册。

## 工作区和持久化背景

Java 工作区根目录通过全局 `home_dir` 配置，例如 `~/.melonAI`。全局配置文件在工作区之外，工作区数据应按 agent 分隔。

Python QwenPaw 兼容结构中，单个 workspace 重点文件和目录包括：

- `agent.json`
- `chats.json`
- `jobs.json`
- `skill.json`
- `credentials.yaml`
- `sessions/{channel}/{user_id}_{session_id}.json`
- `media/`
- `tool_results/`
- `skills/`
- `memory/`
- `.mcp/`
- `browser/`

Chat 兼容的关键点：

- `workspace/chats.json` 存 chat 索引。
- `workspace/sessions/{channel}/{user_id}_{session_id}.json` 存完整 agent state shadow。
- `/api/chats/{id}` 应从 session state 转成 QwenPaw 前端 `Message` schema。
- thinking、text、tool_call、tool_result 必须按 Python 前端协议保持顺序，不能把所有思考或工具协议拼成一大段文本。
- SSE final response 的 `output` 要包含完成版 message/tool/reasoning 序列，刷新历史和流式过程应一致。

## 模型配置背景

Python QwenPaw 的常规模型选择逻辑是：

1. 优先使用当前 workspace `agent.json.active_model`。
2. 如果 agent 没有配置，则 fallback 到全局 active model。

Python 的 `active_model` 结构是：

```json
{
  "provider_id": "deepseek",
  "model": "deepseek-v4-flash"
}
```

Java 当前多处使用字符串形式：

```text
provider:model
```

这可以做兼容，但对前端接口要返回 Python/QwenPaw 期望的对象字段。

Python 还有 `llm_routing` 的 local/cloud 双槽配置，但默认 `enabled=false`。目前没有确认它是 coding mode 专属代码模型，不能把它误当成“代码模型默认开启”。

## Coding Mode 背景

Python 侧 `coding_mode.enabled` 默认是 `false`。Coding Mode 开启后：

- API `GET /coding-mode` 读取当前 agent 的 `agent.json.coding_mode`。
- API `POST /coding-mode` 保存 `coding_mode.enabled` 并 reload agent。
- `coding_mode.project_dir` 表示当前代码项目目录，默认空表示使用 workspace。
- 只有开启后才注入 Coding Mode prompt，并收集 `lsp`、`ast_search` 等代码工具。

Java 侧默认也必须是关闭状态。接口不能硬编码开启，前端调用 `/api/coding-mode` 时应按当前 agent 配置返回真实值。

Coding Mode 相关实现应遵守：

- `GET /api/coding-mode` 返回 `{ enabled, project_dir, agent_id }`。
- 前端开启/关闭使用 `POST /api/coding-mode`。
- 开关变更后需要 reload agent，因为 coding prompt 和 coding tools 在 agent 构建阶段生效。
- 如果实现 `project_dir`，主存储应对齐 Python 的 `agent.json.coding_mode.project_dir`。
- `lsp`、`ast_search` 只有在 `coding_mode.enabled=true` 时进入 toolkit。

## 渠道系统背景

用户最初说不需要渠道系统，后来明确改为必须接入 QwenPaw 支持的渠道，并要求真实可用。

渠道系统目标：

- Java 后端支持 Python QwenPaw registry 中的渠道类型、配置字段、生命周期和前端交互协议。
- 渠道层放在 Spring Boot/WebFlux，不塞进 AgentScope runtime。
- 入站消息统一转成 agent/channel/user/session/content/attachments，再复用现有 `AgentRunner`、`ChatManager`、AgentScope Harness。
- 渠道对话记录也应写入 `workspace/sessions/{channel}/{user_id}_{session_id}.json`，控制台前端能看到完整历史，包括模型回复。

已经重点排查过的问题：

- 微信扫码绑定后消息不能实时回复，只在 Java 启动时回复过。
- QQ 渠道 Java 日志有模型回复，但 QQ 收不到消息，说明 outbound send 或 session/channel address 映射存在问题。
- QQ/微信渠道产生的对话，在控制台页面只看到用户内容，看不到完整模型回复，说明 channel runtime 的 output state 写入或 chat history 转换链路要继续核对。
- `bot_prefix` 不是“用户消息必须以此前缀开头”，而是回复内容默认前缀，不能误解。

## 工具和审批背景

用户期望默认：

- OFF：所有工具都不弹，包括删除。
- AUTO/SMART：系统工具默认不弹，只有 shell 删除类命令弹确认。
- STRICT：严格模式，系统工具都弹确认。

当前 Java 侧思路应保持：

- 系统工具默认不需要授权确认。
- 只有涉及删除/危险 shell 时需要确认。
- 配置应放在全局配置中，但要能按工作区 agent 生效。

工具名和工具调用历史必须对齐 Python/QwenPaw 前端协议。前端工具卡依赖真实工具名和标准 `plugin_call`/`plugin_call_output` 数据结构，不能展示 AgentScope 原始协议文本。

## Skills 背景

Python 项目的内置 skills 已要求放到 Java 工程 resources 下，初始化自动导入。

注意点：

- 技能不需要中英两份，直接使用 QwenPaw 原始技能内容，保留一份。
- 技能池在 workspace 外，工作区内是当前 agent 启用/复制后的 `skills` 目录。
- 新建工作区时，勾选的技能应进入该工作区 `skills` 目录并在 `skill.json`/agent config 中生效。
- 前端技能上传、导入、更新内置技能按钮都要按 Python 行为对齐，不能只返回 not implemented。

## Cron 背景

Java 侧定时任务已经能通过对话创建，前端列表也能看到，但曾出现手动执行无反应的问题。

Cron skill 不能停留在 Python 的 cron 命令文案，需要结合 Java 的 cron 实现重写技能说明，让模型调用 Java 已实现的 `create_cron_job` 等工具。

Cron dispatch 需要支持：

- console
- 已启用的外部 channel
- channel target

## Models 背景

用户曾遇到：

- 模型配置页面保存 key 后，对话仍提示 `Model not configured`。
- Deepseek 报 `Provider is not configured as OpenAI-compatible: deepseek`。
- 添加模型后页面出现重复项。
- 下拉框应展示“该 provider 通过 key 可发现/可用的模型列表”，而不是只展示已经添加过的模型。

后端要对齐 Python ProviderManager 的语义：

- provider config 保存后要能被 Agent runtime 使用。
- provider/model discovery 和 active model 需要分别处理。
- 前端 API 返回字段要兼容 `provider_id`、`model`、`models`、`active_llm` 等结构。

## 前端同步背景

用户要求把 Python QwenPaw 的 `console` 前端同步到 Java 项目中，但去掉 CLI/TUI/桌面应用相关内容。

用户明确要求：

- 前端可以同步复制，但后续不应为了修后端问题去修改前端代码。
- 如果发现 Java 和前端交互不一致，优先修 Java 接口、响应结构和持久化协议。

## 验证要求

常用验证命令：

```bash
mvn -q -DskipTests test-compile
mvn -q -DskipTests package
git diff --check
```

功能改动验收要尽量覆盖前端真实路径：

- 启动 Java 后端，前端 `VITE_API_BASE_URL` 指向 Java 服务。
- 用浏览器/前端页面触发真实操作，而不是只测 Controller 方法。
- 对话类问题要同时检查流式过程、刷新后的历史、workspace session 文件。
- 渠道类问题要同时检查 Java 日志、渠道实际收发、控制台历史是否完整。
- 模型类问题要同时检查 provider 配置保存、active model、生效到 AgentScope runtime。
