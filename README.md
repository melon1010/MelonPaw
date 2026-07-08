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
- **插件系统**：SPI 接口（`melon-plugin-api`）支持第三方扩展
- **前端控制台**：内置 React + TypeScript 控制台（`console/`）
- **定时任务**：Cron 调度器 + 心跳任务
- **备份与恢复**：工作区数据打包/还原

---

## 🏗️ 项目结构

```
melonPaw-java/
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
├── melon-app/            # Spring Boot 应用：HTTP Controller、Service、CLI、Runner
│   ├── controller/       #   REST API（28 个 Controller）
│   ├── service/          #   业务服务层
│   ├── runner/           #   Agent 运行器、聊天管理、SSE 事件映射
│   ├── cli/              #   Picocli 命令行（app/init/agents/config/skills/models/doctor/cron/env）
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
                              └── melon-coding-mode
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
git clone https://github.com/melon1010/melonPaw-java.git
cd melonPaw-java
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

## 📡 主要 API


| 路径                                  | 说明             |
| ------------------------------------- | ---------------- |
| `POST /api/console/chat`              | 流式聊天（SSE）  |
| `GET  /api/agents`                    | Agent 列表       |
| `GET  /api/sessions`                  | 会话列表         |
| `POST /api/plan/*`                    | 计划模式         |
| `POST /api/approval/*`                | 审批确认         |
| `GET  /api/providers` / `/api/models` | 模型供应商与模型 |
| `GET  /api/workspace`                 | 工作区信息       |
| `POST /api/backup/*`                  | 备份/恢复        |
| `GET  /api/crons`                     | 定时任务         |

> 完整接口见各 Controller 的 `@RequestMapping`。前端控制台使用一套兼容路由（`*CompatController`）。

---

## 💻 CLI 使用

```bash
# 打包后通过 spring-boot:run 或 java -jar 启动，也可用 picocli 子命令：
melon app        # 启动 HTTP 服务
melon init       # 初始化工作区
melon agents     # 管理 Agent
melon config     # 查看/修改配置
melon skills     # 管理技能
melon models     # 列出可用模型
melon doctor     # 体检（检查 API Key、依赖、路径等）
melon cron       # 管理定时任务
melon env        # 环境变量管理
```

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
| 前端         | React、TypeScript、Vite                 |

---

## 📄 许可证

本项目采用 [Apache License 2.0](LICENSE)。

公开发布前请确认 `NOTICE`、`pom.xml`、README 中的仓库地址、版权主体和第三方资源许可证均准确无误。

---

## 📌 相关

- 代码质量分析报告见 [CODE_ANALYSIS.md](doc/CODE_ANALYSIS.md)
- 前端控制台说明见 [console/REMADE.md](./console/REMADE.md)
