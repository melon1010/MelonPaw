# melonPaw-Java

> AI Agent 平台，基于 **AgentScope 2.0 + Spring Boot 3 (WebFlux) + Java 17** 实现。
> 本项目是 [QwenPaw](https://github.com/agentscope-ai/QwenPaw) 的 Java 后端版本，复刻自成熟的 Python 原型，提供 HTTP API、CLI 与前端控制台。

---

## ✨ 特性

- **多 Agent 运行时**：基于 AgentScope 2.0 harness，支持多 Agent、子 Agent 派发、任务跟踪
- **20+ 内置工具**：文件 IO、Shell 执行、Grep/Glob 搜索、浏览器自动化（Playwright）、截图、多 Agent 协作等
- **多模型供应商**：DashScope（通义千问）、OpenAI、Anthropic（Claude）、Gemini、DeepSeek、Ollama
- **中间件链**：自动续写、编码模式、媒体过滤、记忆注入、计划门控、Token 记录、工具守卫、系统提示词
- **三种 Agent 模板**：`default`（全能）、`local`（本地快速）、`qa`（测试/代码审查）
- **计划模式 & 审批机制**：AUTO / SMART 多级审批，敏感操作需人工确认
- **流式响应**：基于 WebFlux + SSE（Server-Sent Events）实时推送
- **聊天与 Coding 模式**：支持多会话聊天、任务停止/查询、文件树、Monaco 预览/编辑、Git 状态与提交操作
- **渠道接入**：内置 Console、HTTP、MQTT、OneBot 以及钉钉、飞书、微信、企业微信、Slack、Telegram、Discord、Matrix 等渠道配置与访问控制
- **技能系统**：支持技能池、内置技能导入、Hub 安装、ZIP 上传、启停、标签、渠道绑定、配置编辑和 AI 优化
- **MCP 与 ACP**：支持 MCP 客户端/Server 管理、工具授权策略、OAuth 状态跟踪，并提供 ACP 配置页面
- **插件系统**：SPI 接口（`melon-plugin-api`）支持第三方扩展，前端支持插件菜单、路由和 Slot 扩展
- **前端控制台**：内置 React + TypeScript + Vite 控制台（`console/`），支持 Web 浏览器访问
- **定时任务**：Cron 调度器、立即执行、暂停/恢复、历史记录和心跳任务
- **备份与恢复**：工作区数据打包/还原
- **运维与安全**：环境变量管理、Token 用量统计、审计日志、文件守卫、工具守卫、技能扫描、本地模型下载与调试日志

---

## 🏗️ 项目结构

```
MelonPaw/
├── melon-plugin-api/     # 插件 SPI 接口（MelonPlugin, PluginContext, PluginTool）
├── melon-core/           # 核心：Agent 运行时、中间件、配置、Provider、计划、安全
│   ├── agent/            #   Agent 模板、多 Agent 管理、工作区、消息处理工具
│   ├── config/           #   配置体系（AgentConfig, MelonConfig, ToolsConfig 等）
│   ├── middleware/       #   8 个请求中间件
│   ├── provider/         #   模型供应商管理（6 家）
│   ├── plan/             #   计划模式状态机
│   ├── plugin/           #   插件管理器实现
│   ├── prompt/           #   系统提示词构建
│   └── security/         #   密钥存储、技能扫描
├── melon-tools/          # 20+ 内置工具实现
│   ├── fileio/           #   文件读写/编辑/搜索
│   ├── shell/            #   Shell 命令执行
│   ├── browser/          #   浏览器自动化（Playwright）
│   ├── agent/            #   多 Agent 协作工具
│   ├── media/            #   截图/图片/视频查看
│   └── util/             #   时间、Token、文件下发
├── melon-coding-mode/    # 编码模式：LSP 集成 + AST 搜索（ast-grep）
├── melon-channels/       # 多渠道消息接入、队列、二维码、访问控制和适配器注册
├── melon-app/            # Spring Boot 应用：HTTP Controller、Service、CLI、Runner
│   ├── controller/       #   REST API 与前端兼容接口
│   ├── service/          #   业务服务层
│   ├── runner/           #   Agent 运行器、聊天管理、SSE 事件映射
│   ├── cli/              #   Picocli 命令行
│   ├── cron/             #   定时任务管理
│   ├── mcp/              #   MCP（Model Context Protocol）客户端管理
│   └── auth/             #   认证服务
├── console/              # 前端控制台（React + TypeScript + Vite）
└── pom.xml               # Maven 父 POM
```

### 模块依赖关系

```
melon-plugin-api  ←──  melon-core  ←──  melon-tools
                              ↑              ↑
                              ├── melon-coding-mode
                              └── melon-channels
                                      ↑
                          melon-app（聚合所有模块）
```

---

## 🚀 快速开始

### 环境要求


| 依赖    | 版本                |
| ------- | ------------------- |
| JDK     | 17+                 |
| Maven   | 3.8+                |
| Node.js | 18+（仅前端控制台） |

### 1. 克隆并构建

```bash
git clone <your-repository-url>
cd MelonPaw
mvn clean install -DskipTests
```

### 2. 配置模型 API Key

至少配置一个供应商的环境变量（推荐 DashScope）：

```bash
# 通义千问（DashScope）
export DASHSCOPE_API_KEY=sk-xxxx

# 或 OpenAI
export OPENAI_API_KEY=sk-xxxx

# 或 Anthropic / Gemini / DeepSeek
export ANTHROPIC_API_KEY=sk-xxxx
export GEMINI_API_KEY=xxxx
export DEEPSEEK_API_KEY=sk-xxxx
```

> 也可以在 `~/.melonAI/providers.json` 中按供应商写入 `api_key` / `base_url`，优先级高于环境变量。
> Ollama 为本地部署，无需 API Key。

### 3. 启动后端

```bash
mvn -pl melon-app spring-boot:run
```

默认监听 `http://127.0.0.1:8088`。配置文件位于 `melon-app/src/main/resources/application.yaml`。

### 4. （可选）启动前端控制台

```bash
cd console
npm install
npm run dev
```

Vite 默认运行在 `http://localhost:5173`，并将 `/api` 代理到 `http://localhost:8088`。
如需直连后端：`VITE_API_BASE_URL=http://localhost:8088 npm run dev`。

常用前端命令：

```bash
cd console
npm run build        # 类型检查并构建 Web 控制台
npm run test:run     # 运行 Vitest 测试
npm run lint         # ESLint
```

---

## 🛠️ 配置说明

启动配置文件：`melon-app/src/main/resources/application.yaml`

```yaml
server:
  port: 8088
  host: 127.0.0.1

melon:
  home_dir: ~/.melonAI              # 用户数据根目录
```

Agent、模型、工具、技能等运行时配置保存在 `~/.melonAI/config.yaml` 和前端配置接口写入的状态文件中；
`application.yaml` 只保留 Spring 启动和全局 home 配置。

### Agent 模板


| 模板      | 模型       | 工具范围              | 用途                 |
| --------- | ---------- | --------------------- | -------------------- |
| `default` | qwen-plus  | 全部工具 + 浏览器     | 标准全能 Agent       |
| `local`   | qwen-turbo | 仅文件/Shell          | 本地快速任务，无网络 |
| `qa`      | qwen-plus  | 文件/Shell + 多 Agent | 代码审查与测试       |

### 模型 ID 格式

`<provider>:<model>`，例如：

- `dashscope:qwen-plus`
- `openai:gpt-4o`
- `anthropic:claude-3-5-sonnet-20241022`
- `gemini:gemini-2.0-flash`
- `deepseek:deepseek-chat`
- `ollama:llama3.1`

---

## 🖥️ 前端控制台功能

控制台路由由 `console/src/layouts/registry/builtinRoutes.tsx` 和 `builtinMenu.ts` 注册，核心页面包括：

| 页面 | 功能 |
| ---- | ---- |
| `/chat` | 多会话聊天、流式输出、上传、审批、Token 用量、消息队列 |
| `/coding` | Coding 模式、文件树、文件预览/编辑、Git 状态、分支、diff、stage、commit |
| `/inbox` | 推送消息、审批卡片、Harvest 内容、Trace 查看 |
| `/channels` | 渠道类型、渠道配置、启动/停止/重启、健康检查、二维码和访问控制 |
| `/sessions` | 会话列表、筛选、详情抽屉 |
| `/cron-jobs` / `/heartbeat` | 定时任务管理、手动运行、暂停/恢复、心跳配置 |
| `/workspace` | Agent 工作区文件、记忆、系统提示词、代码文件管理 |
| `/skills` / `/skill-pool` | 技能启停、导入、创建、上传、标签、渠道绑定、内置技能同步 |
| `/tools` | 内置工具启停、异步执行开关和工具配置 |
| `/mcp` / `/acp` | MCP 客户端、Server、工具授权、OAuth 状态和 ACP 配置 |
| `/agents` / `/agent-config` | Agent 增删改查、排序、启停、运行参数和安全级别配置 |
| `/models` | 供应商配置、模型增删、连接测试、OpenRouter 过滤、本地模型下载 |
| `/environments` | 环境变量增删改查 |
| `/security` | 工具守卫、文件守卫、技能扫描、审计事件和免认证主机 |
| `/token-usage` / `/agent-stats` | Token 明细、聚合统计和 Agent 指标 |
| `/backups` | 备份创建、恢复、导入、导出和删除 |
| `/voice-transcription` | 语音输入、本地 Whisper 状态和转写供应商配置 |
| `/debug` / `/plugin-manager` | 后端日志、调试信息、官方/市场插件安装与卸载 |

---

## 📡 主要 API


| 路径 | 说明 |
| ---- | ---- |
| `POST /api/console/chat` | 流式聊天（SSE）、任务提交、停止、上传和 Inbox/Debug 兼容接口 |
| `/api/chats` | 会话列表、创建、更新、删除和批量删除 |
| `/api/agents` | Agent 列表、详情、创建、更新、删除、排序、启停 |
| `/api/models` / `/api/providers` | 模型供应商、活跃模型、模型探测、自定义 Provider、OpenRouter、本地模型 |
| `/api/workspace` | 工作区配置、运行配置、文件/记忆/代码文件读写、下载、上传、监听、语音转写 |
| `/api/workspace/coding-project` | Coding 项目选择、创建、导入、本地目录浏览、ZIP 上传和 Git clone |
| `/api/workspace/git` | Git status、branches、checkout、diff、stage、commit、log、discard、revert |
| `/api/skills` | 技能列表、刷新、技能池、Hub 安装、导入、上传、启停、标签、渠道和配置 |
| `/api/tools` | 工具列表、启停、异步执行开关和工具配置 |
| `/api/mcp` | MCP 客户端/Server、工具列表、访问策略、OAuth、reload |
| `/api/config/channels` / `/api/channels/*` | 渠道类型、配置、健康检查、二维码、Webhook、WebSocket |
| `/api/access-control` | 渠道白名单、黑名单、待审批用户、备注和用户名 |
| `/api/cron/jobs` | Cron 任务创建、更新、删除、运行、暂停、恢复、状态和历史 |
| `/api/backups` | 备份列表、创建流、恢复、删除、导入和导出 |
| `/api/envs` | 环境变量列表、读取、写入、删除 |
| `/api/plugins` | 插件列表、目录、市场搜索、安装、上传、状态、reload、卸载 |
| `/api/token-usage` | Token 汇总和明细 |
| `/api/config/security/*` | 工具守卫、文件守卫、技能扫描、审计事件、免认证主机 |
| `/api/auth` | 登录、注册、状态、验证、资料更新和退出 |
| `/api/files/preview/{path}` | 工作区文件预览 |

> 完整接口见各 Controller 的 `@RequestMapping`。前端控制台使用一套兼容路由（`*CompatController`）。

---

## 💻 CLI 使用

```bash
# 打包后通过 spring-boot:run 或 java -jar 启动，也可用 picocli 子命令：
melonpaw app             # 启动 HTTP 服务
melonpaw init            # 初始化工作区
melonpaw agents          # 管理 Agent，也可直接 chat
melonpaw chats           # 管理聊天会话
melonpaw task            # 无界面执行单次任务
melonpaw config          # 查看/修改配置
melonpaw skills          # 管理技能、技能池、安装任务和技能配置
melonpaw models          # 管理供应商、模型、本地模型下载和活跃模型
melonpaw channels        # 管理渠道配置、健康检查、二维码、访问控制和 Webhook 测试
melonpaw plugin          # 插件列表、市场搜索、安装、验证、reload、卸载
melonpaw cron            # 管理定时任务
melonpaw env             # 环境变量管理
melonpaw daemon          # 运行中服务状态、重启、日志和版本
melonpaw doctor          # 体检（检查 API Key、依赖、路径等）
melonpaw desktop         # 在系统浏览器打开控制台
melonpaw shutdown        # 停止运行中的服务
melonpaw clean           # 清理本地 home 数据（支持 dry-run）
```

全局选项支持 `--host`、`--port`、`--base-url`、`--profile`、`--format plain|json|table` 和 `--timeout`。

---

## 🧪 测试与自检

项目内置若干自检程序（`melon-app/src/test/.../*SelfCheck.java`），可通过其 `main` 方法手动运行：

```bash
# 例如验证 ChatManager 行为
mvn -pl melon-app exec:java -Dexec.mainClass="com.melon.app.runner.ChatManagerSelfCheck"
```

涵盖：聊天会话隔离、会话影子落盘、大工具输出溢出、上下文压缩摘要、遗留数据迁移等。

### 代码规范与质量检查

建议新代码遵循 [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) 的命名、导入与结构约定，并结合 Spring 项目常见实践：

- Java 17、4 空格缩进、UTF-8、LF 换行。
- 优先构造器注入，避免新增字段注入。
- Controller 保持薄层，业务逻辑进入 Service/Core 层。
- 文件、Shell、浏览器自动化、插件/技能加载等敏感能力必须经过现有安全边界。
- 老代码采用增量收敛，不建议把格式化和行为变更混在同一个 PR。

可选质量检查：

```bash
mvn -Pquality checkstyle:check
cd console && npm run test:run
cd console && npm run build
```

更多约定见 [CONTRIBUTING.md](CONTRIBUTING.md) 和 [开源发布检查清单](docs/OPEN_SOURCE_CHECKLIST.md)。

---

## 🧩 插件开发

实现 `melon-plugin-api` 中的接口即可：

```java
public class MyPlugin implements MelonPlugin {
    @Override
    public String id() { return "my-plugin"; }

    @Override
    public List<PluginTool> tools() {
        return List.of(new MyTool());
    }

    @Override
    public void onEnable(PluginContext ctx) { /* ... */ }
}
```

打包后放入 `~/.melonAI/plugins/`，由 `PluginManager` 自动发现加载。

---

## 🔒 安全须知

- **工作区隔离**：`SafePathUtil` 提供目录穿越防护，工具操作被限制在工作区内
- **审批机制**：`SMART` 级别下，Shell 执行等敏感操作需人工确认
- **技能扫描**：`SkillScanner` 对动态加载的技能进行安全检查
- **密钥存储**：API Key 通过环境变量或加密的 `SecretStore` 管理
- **渠道访问控制**：渠道用户支持白名单、黑名单、待审批和备注信息
- **审计与守卫**：工具守卫、文件守卫、技能扫描历史和审计事件可在控制台查看

---

## 📦 技术栈


| 层           | 技术                                    |
| ------------ | --------------------------------------- |
| 运行时       | Java 17、Spring Boot 3.3.0              |
| Agent 框架   | AgentScope 2.0（harness + core）        |
| Web          | Spring WebFlux（响应式）、SSE           |
| CLI          | Picocli 4.7.6                           |
| 浏览器自动化 | Playwright 1.45                         |
| 代码智能     | LSP4j 0.23（LSP）、ast-grep（AST 搜索） |
| 渠道接入     | MQTT（Paho）、二维码（ZXing）、HTTP/WebSocket |
| 前端         | React、TypeScript、Vite、Ant Design、Monaco Editor、Zustand |

---

## 📄 许可证

本项目采用 [Apache License 2.0](LICENSE)。

公开发布前请确认 `NOTICE`、`pom.xml`、README 中的仓库地址、版权主体和第三方资源许可证均准确无误。

---

## 📌 相关

- 贡献指南见 [CONTRIBUTING.md](CONTRIBUTING.md)
- 安全策略见 [SECURITY.md](SECURITY.md)
