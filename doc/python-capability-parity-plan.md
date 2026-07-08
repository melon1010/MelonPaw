# melonPaw Java Capability Parity Plan

## Background

This Java project is intended to reproduce the Python-side backend and agent capabilities from `/Users/melon/IdeaWorkSpace/Github/melonPaw` using AgentScope 2.0 and Spring Boot.

The current Java codebase already has the broad module shape:

- `melon-core`: configuration, agent construction, middleware, providers, plugins, state, prompts.
- `melon-tools`: built-in tool implementations.
- `melon-coding-mode`: LSP and AST search tools.
- `melon-app`: Spring Boot controllers, services, CLI entry classes, cron, backup, sessions.
- `melon-plugin-api`: Java plugin API.

However, several important Python capabilities are still missing or only implemented as placeholders. This document defines the adjusted implementation plan.

## Explicitly Out of Scope

The following Python-side capabilities are intentionally not required for the Java project at this stage:

- Channel system: DingTalk, Feishu, WeChat, QQ, Telegram, Slack, Discord, Mattermost, Matrix, MQTT, iMessage, WeCom, voice/SIP channel integrations, channel cards, QR login, channel access control.
- CLI/TUI product surface: Python `melonpaw` TUI, rich CLI parity, daemon commands, desktop commands, update commands, installer commands.
- Desktop packaging: Tauri app, PyInstaller packaging, platform installer scripts, desktop auto-update.
- Frontend console parity: the Java backend may expose compatible APIs where useful, but full console/Tauri feature parity is not a goal in this phase.

## Target Scope

The Java backend should focus on these capabilities:

- Agent runtime parity for HTTP/SSE usage.
- Proper AgentScope 2.0 agent construction with tools, middleware, memory, state, and model providers.
- Built-in tool execution parity for shell, files, search, browser, media viewing, token usage, multi-agent, skills, LSP, and AST search.
- MCP runtime with real JSON-RPC communication and tool registration.
- Skill system for built-in skills, workspace skills, skill pool operations, enable/disable, metadata, validation, and materialization.
- Approval/governance for tool execution.
- Session, chat, task, token usage, backup, cron, workspace, agent management APIs.
- Provider and local model management sufficient for Java backend operation.
- Testing and documentation.

## Current Critical Gaps

### 1. Agent Toolkit Registration Is Not Closed

Java has many `ToolBase` classes under `melon-tools`, but `MelonAgentFactory` does not currently build a `Toolkit` and register:

- built-in tools,
- coding-mode tools,
- MCP tools,
- plugin tools,
- memory tools,
- skill-related tools.

Without this, the agent may be able to chat but cannot reliably execute the operational capabilities expected from melonPaw.

### 2. Approval and ToolGuard Are Not Wired Into Execution

`ToolGuardMiddleware` and `ApprovalService` exist, but they are not a complete execution gate:

- `ToolGuardMiddleware` is not added in `MelonAgentFactory.buildMiddlewares()`.
- Approval events are not clearly connected to `ApprovalService`.
- Tool execution does not appear to block until approve/deny.
- There is no persistent policy/rule/audit model comparable to Python governance.

### 3. MCP Is Placeholder-Level

`McpClientManager` explicitly states that it does not perform real JSON-RPC communication. Tool discovery and tool invocation currently return simulated data.

### 4. Workspace Runtime Is Too Thin

Python `Workspace` owns session, memory, driver manager, cron, plugins, local workspace, task tracker, watchers, and runtime. Java currently has `MultiAgentManager` managing `HarnessAgent` instances, but does not yet have an equivalent runtime service container per agent.

### 5. Skill System Is Directory CRUD

Java `SkillService` can list/read/create/delete simple `SKILL.md` directories. Python supports a much richer model:

- global skill pool,
- workspace skill manifest,
- built-in skill initialization/import,
- enable/disable,
- tags/config,
- zip upload/import/export,
- scanner validation,
- hub/market installation,
- `materialize_skill`,
- slash-command-like skill triggering.

### 6. Memory and Context Management Are Incomplete

Java currently uses basic AgentScope memory configuration and middleware names. Python has:

- pluggable memory backends,
- auto memory search,
- auto memory write,
- memory tools,
- compact-before-memory flush,
- scroll context/history,
- tool result pruning/offload.

### 7. Provider Management Is Basic

Java provider management is mostly static provider/model lists plus API-key environment checks. Python has dynamic provider configuration, active model slots, OAuth/free providers, custom providers, local model management, and richer connection tests.

## Implementation Phases

## Phase 0: Baseline Build and Version Alignment

Goal: make the Java project reliably buildable before deeper feature work.

Tasks:

- Confirm the current AgentScope Java API surface and update code to compile cleanly.
- Align dependency versions with the intended latest AgentScope 2.0 Java release.
- Add a root `README.md` with Java 17, Maven, environment variables, and startup instructions.
- Add minimal smoke tests for Spring context startup and `MelonAgentFactory` construction.
- Decide whether generated `target/` and IDE files should be ignored via `.gitignore`.

Deliverables:

- `mvn test` or a documented equivalent passes locally.
- Build failure causes are documented if external AgentScope artifacts are unavailable.
- Minimal developer docs exist.

## Phase 1: Agent Runtime and Toolkit Closure

Goal: make the AgentScope agent capable of actually using tools.

Tasks:

- Introduce a Java `BuiltinToolRegistry` that maps configured tool names to `ToolBase` instances.
- Build a `Toolkit` in `MelonAgentFactory`.
- Register enabled tools from `ToolsConfig.builtin_tools`.
- Register coding tools only when coding mode is enabled.
- Register `run_tool_batch` after the base toolkit exists.
- Register plugin tools from `PluginManager`.
- Register MCP tools after the MCP runtime is real.
- Add stable tool metadata: name, description, icon, async flag, display-to-user flag.
- Ensure runtime context passes working directory, project directory, user ID, session ID, channel/source, and agent ID to tools.

Deliverables:

- A configured agent can call at least `read_file`, `write_file`, `grep_search`, `execute_shell_command`, and `get_current_time`.
- Tool enable/disable config changes affect newly created/reloaded agents.
- Unit tests cover tool registry filtering and disabled tools.

## Phase 2: Tool Parity Core

Goal: bring built-in tools to functional parity where Java already has corresponding classes.

Tasks:

- Review every Java tool against Python behavior:
  - shell execution,
  - file read/write/edit/append,
  - grep/glob,
  - browser automation,
  - desktop screenshot,
  - view image/video,
  - send file to user,
  - token usage,
  - multi-agent tools,
  - materialize skill,
  - AST search,
  - LSP.
- Add missing `append_file` to config if the tool is intended to exist.
- Normalize tool outputs into a consistent `ToolResultBlock` shape.
- Implement truncation behavior similar to Python `truncate_text_output`.
- Add path safety and workspace boundary checks.
- Add async execution behavior for long-running tools.
- Add cancellation support where AgentScope allows it.

Deliverables:

- Tool behavior tests for success, validation errors, large output truncation, and path safety.
- A simple end-to-end agent request can read/edit/search files in a workspace.

## Phase 3: Approval and Governance

Goal: implement a real tool execution gate.

Tasks:

- Add `ToolGuardMiddleware` to the agent middleware chain.
- Replace event-only approval with a blocking approval lifecycle:
  - create pending approval,
  - publish SSE event,
  - wait for approve/deny,
  - continue or return denied result.
- Support approval levels:
  - `OFF`: allow all,
  - `AUTO`: allow with logging,
  - `SMART`: ask for risky tools and risky arguments,
  - `STRICT`: ask for all tools.
- Port key Python governance concepts:
  - detector rules,
  - builtin/user rules,
  - allow/deny/ask decisions,
  - rule generalization after approval,
  - audit records.
- Wire `ApprovalController` and `PlanController` into the same runtime session model.

Deliverables:

- Tool calls requiring approval pause and resume correctly over HTTP/SSE.
- Denied tools return a structured denial result.
- Approval decisions are testable without a frontend.

## Phase 4: Real MCP Runtime

Goal: replace placeholder MCP with real MCP client behavior.

Tasks:

- Choose a Java MCP SDK or implement minimal JSON-RPC client support.
- Support stdio transport.
- Support HTTP/SSE or streamable HTTP transport as required by the MCP version used.
- Implement:
  - initialize,
  - tools/list,
  - tools/call,
  - connection lifecycle,
  - reconnect/reload,
  - per-server enable/disable,
  - tool whitelist/blacklist.
- Convert MCP tools into AgentScope `ToolBase` or equivalent registered tools.
- Store MCP configs under the agent/workspace, not only in process memory.
- Add secret/env binding support for MCP command/env/header configuration.

Deliverables:

- Java can connect to a local stdio MCP echo server and call a tool.
- MCP tools appear in the agent toolkit.
- Controller endpoints return real discovered tools and real call results.

## Phase 5: Workspace Runtime Refactor

Goal: introduce an agent-scoped runtime container similar to Python `Workspace`, without implementing channels.

Tasks:

- Add a Java `Workspace` or `AgentWorkspace` abstraction owning:
  - agent ID,
  - workspace directory,
  - `HarnessAgent`,
  - session store,
  - chat manager,
  - task tracker,
  - memory manager,
  - MCP/driver manager,
  - cron manager,
  - plugin registry/context,
  - skill service.
- Refactor `MultiAgentManager` to manage workspaces rather than raw `HarnessAgent` only.
- Add lifecycle methods:
  - start,
  - stop,
  - reload,
  - graceful reload with active task awareness.
- Keep channels explicitly out of this abstraction for now.

Deliverables:

- Each configured agent has isolated workspace state and services.
- Reloading one agent does not disrupt others.
- Existing controllers route through the workspace abstraction.

## Phase 6: Session, Chat, Task, and SSE Runtime

Goal: make HTTP/SSE conversations durable and operational.

Tasks:

- Expand session persistence beyond raw AgentScope state if needed.
- Add chat metadata storage comparable to Python `chats.json`.
- Support session list/get/create/delete/rename.
- Add background task tracking for async agent runs.
- Add tool-call tracking endpoints if needed:
  - list tool calls,
  - inspect tool call,
  - stream output,
  - cancel tool call,
  - offload large output.
- Improve `SseEventMapper` so events match a stable frontend/API contract.

Deliverables:

- A user can resume a session after restart.
- SSE includes text deltas, tool calls, tool results, approval requests, errors, and completion.
- Background tasks can be queried.

## Phase 7: Skills System

Goal: port the Python skill system in a backend-focused form.

Tasks:

- Define Java equivalents for:
  - skill metadata,
  - skill pool manifest,
  - workspace skill manifest,
  - enabled/disabled state,
  - tags,
  - skill config.
- Initialize built-in skills from resources.
- Support workspace skill install/remove/enable/disable.
- Support pool import from directory and zip.
- Port skill scanner validation rules where practical.
- Implement `materialize_skill` fully:
  - normalize skill name,
  - render frontmatter,
  - write `SKILL.md`,
  - write extra files,
  - validate,
  - enable in workspace.
- Add prompt integration so enabled skills are visible to the agent.

Deliverables:

- Enabled skills appear in the agent system prompt or equivalent skill registry.
- `materialize_skill` creates a usable workspace skill.
- Skill CRUD and enable/disable are covered by tests.

## Phase 8: Memory and Context

Goal: provide practical Java equivalents of Python memory behavior.

Tasks:

- Define a `MemoryManager` interface.
- Implement a simple file-backed memory manager first.
- Add memory tools:
  - search memory,
  - write memory,
  - inspect memory if needed.
- Add middleware for:
  - memory prompt injection,
  - auto memory search before model call,
  - auto memory write after turns,
  - compact-before-memory flush.
- Decide whether to port ReMe/ADBPG backends or keep Java-native file/vector storage initially.
- Add tool result pruning/offload to protect context.
- Consider scroll context/history only after core memory works.

Deliverables:

- Memory survives restart.
- Agent can retrieve relevant prior memory in a later request.
- Large tool outputs do not exhaust context.

## Phase 9: Provider and Model Management

Goal: make model configuration usable beyond static environment variables.

Tasks:

- Expand provider config model:
  - provider ID,
  - display name,
  - base URL,
  - API key reference,
  - model list,
  - capabilities,
  - enabled flag.
- Support active model slots if needed:
  - default,
  - summary,
  - coding,
  - memory.
- Add custom OpenAI-compatible provider support.
- Add provider connection tests using a small model request where safe.
- Add local model management only if Java runtime will directly manage local processes.
- Keep OAuth/free-provider work after basic configurable providers.

Deliverables:

- Users can configure OpenAI-compatible providers without code changes.
- Provider settings persist.
- Agent reload picks up model changes.

## Phase 10: Cron, Backup, Workspace, and Agent APIs

Goal: improve existing Java controllers from basic CRUD to operational parity for backend use.

Tasks:

- Cron:
  - job list/get/create/update/delete,
  - pause/resume/run now,
  - execution history,
  - timezone handling,
  - session/source metadata.
- Backup:
  - include agents, config, secrets, skills, workspace files,
  - import/export zip,
  - restore with agent stop/restart coordination.
- Workspace:
  - project directory management,
  - file upload/download/delete safety,
  - coding project APIs if coding mode requires them.
- Agent APIs:
  - create/update/delete agents,
  - reload/start/stop,
  - scoped config,
  - stats.

Deliverables:

- Existing basic controllers are upgraded to durable, agent-scoped operations.
- Restore/reload flows do not leave active agents in inconsistent states.

## Phase 11: Plugin System Integration

Goal: connect Java plugin loading to the agent runtime.

Tasks:

- Keep JAR plugin loading, but define how plugins contribute:
  - tools,
  - prompt contributors,
  - lifecycle hooks,
  - workspace-created hooks,
  - config pages/metadata if needed later.
- Register plugin tools into each workspace toolkit.
- Provide plugin data directory and workspace context.
- Add unload/reload behavior that refreshes affected agents.

Deliverables:

- A sample Java plugin can contribute a tool and the agent can call it.
- Plugin reload updates available tools.

## Phase 12: Testing and Compatibility Matrix

Goal: make progress measurable.

Tasks:

- Add unit tests for:
  - config load/save/default merge,
  - tool registry,
  - approval decisions,
  - skill manifests,
  - MCP config parsing,
  - provider config.
- Add integration tests for:
  - Spring context startup,
  - agent query without tools,
  - agent query with a simple tool,
  - MCP echo server,
  - approval pause/resume,
  - session resume.
- Add a parity matrix document mapping Python modules to Java status:
  - implemented,
  - partial,
  - planned,
  - intentionally out of scope.

Deliverables:

- A repeatable test command is documented.
- The parity matrix is updated whenever a phase completes.

## Recommended Execution Order

1. Phase 0: build and version baseline.
2. Phase 1: toolkit registration.
3. Phase 2: core tool parity.
4. Phase 3: approval/governance.
5. Phase 4: real MCP runtime.
6. Phase 5: workspace runtime.
7. Phase 6: session/chat/task/SSE.
8. Phase 7: skills.
9. Phase 8: memory/context.
10. Phase 9: providers.
11. Phase 10: cron/backup/workspace/agent APIs.
12. Phase 11: plugin integration.
13. Phase 12: testing and compatibility matrix.

## First Milestone Definition

The first practical milestone should be:

> A Spring Boot Java backend can start, create a default AgentScope agent, register enabled built-in tools, stream an HTTP/SSE response, call `read_file` and `execute_shell_command`, enforce approval for a risky shell command, and persist a resumable session.

This milestone deliberately excludes channels, CLI/TUI, desktop packaging, and frontend console parity.
