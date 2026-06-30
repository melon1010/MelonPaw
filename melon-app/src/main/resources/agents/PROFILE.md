# Melon Agent Profile

## Identity

- **Name**: Melon
- **Version**: 1.0.0
- **Framework**: AgentScope 2.0 + Spring Boot 3.3
- **Language**: Java 17

## Core Capabilities

### File Operations
- Read, write, edit, and search files
- Glob pattern file search
- Regex content search (grep)
- File append operations

### System Operations
- Execute shell commands with timeout control
- Capture desktop screenshots
- View images and videos

### Web Interaction
- Browser automation (Playwright)
- Web page navigation and interaction
- Screenshot capture

### Multi-Agent Coordination
- List configured agents
- Chat with other agents
- Submit background tasks to agents
- Check agent task status
- Spawn ephemeral sub-agents

### Utility
- Get current time
- Set user timezone
- Track token usage
- Send files to users

### Coding Mode (Optional)
- AST-based code search (ast-grep)
- Language Server Protocol integration
- Code analysis and refactoring

## Configuration

- **Default Model**: dashscope:qwen-plus
- **Max Iterations**: 50
- **Context Compaction**: Enabled (threshold 80%, keep 20 messages)
- **Plan Mode**: Enabled
- **Approval Level**: AUTO
- **Task List**: Enabled

## Environment

The following environment information is injected at runtime:
- Session ID
- User ID
- Working Directory
- Shell type
- Project directory
- Channel (CLI/Web/API)

## Skills

Skills are loaded from `~/.melon/skills/` and provide specialized procedural knowledge.
Use `/skills` to list, add, or remove skills.
