# CLI Compatibility

MelonPaw keeps a generic Java CLI core under the single `melonpaw` command. This document is the public CLI ledger for the open-source project.

Status values:

- `supported`: implemented with shared CLI infrastructure and covered by tests.
- `partial`: usable, but not every option or behavior is implemented.
- `missing`: not registered yet.
- `deferred`: intentionally out of scope for this Java CLI layer; the command prints a clear explanation.

## Root Commands

| Command | MelonPaw status | Notes |
|---|---|---|
| `app` | partial | Starts Spring Boot; accepts common flags. |
| `init` | partial | Uses shared path resolver and initializes workspace/home skeleton. |
| `agents` / `agent` | partial | Management commands use HTTP specs; `chat` submits to `/api/console/chat/task` and polls non-streaming output. |
| `models` | partial | Provider/model CRUD plus local model `local/download/download-status/cancel-download/remove-local` use existing Java endpoints. |
| `skills` | partial | Workspace skill CRUD plus `install/install-status/import-builtin/test` use existing JSON endpoints. |
| `cron` | partial | Primary command; `crons` kept as legacy alias. |
| `env` | partial | Primary command; `envs` kept as legacy alias. |
| `chats` / `chat` | partial | Basic HTTP CRUD commands registered. |
| `channels` / `channel` | partial | Types/meta/config/start/stop/restart/health/qrcode/access-control/webhook are mapped to Java endpoints. |
| `plugin` | partial | HTTP plugin commands plus local `validate` for directory/zip descriptors. |
| `doctor` | supported | Health check plus safe `fix` for local directories/config skeleton. |
| `clean` | supported | Clears Melon home children with `--dry-run` and required `--yes`. |
| `shutdown` | partial | Default calls backend shutdown endpoint; `--force` kills the process listening on the configured port. |
| `task` | partial | Submits `/api/console/chat/task` and polls until completion. |
| `desktop` | partial | CLI opens the console URL in the system browser. |
| `auth` | partial | `reset-password` explains env-based auth; no unsafe local credential mutation. |
| `daemon` | partial | Reports local paths/version/logs, supports `logs --backend`, and prints restart guidance; no daemon supervisor. |
| `tui` | supported | Java terminal chat loop with session resume, project directory context, and bare project path launch. |
| `acp` | deferred | Requires a Java ACP runtime before implementation; command explains this explicitly. |
| `update` | supported | Informational command; open-source Java build does not self-update. |
| `uninstall` | supported | Informational command; open-source Java build does not self-uninstall. |
| `auto` | supported | Lists registered `CliCommandSpecs` as the lightweight generated-command view. |

## Deferred Scope

These compatibility surfaces are intentionally deferred, not hidden missing work:

- `acp`: no Java ACP server/runtime exists in this project yet.
- Textual-specific widgets: Java TUI uses a portable line-mode terminal loop instead of Python Textual.
- `update` / `uninstall`: implemented as informational commands; self-mutating installers are not part of the open-source Java build.

## Design Rules

- New HTTP-backed commands should be declared in `CliCommandSpecs` and executed through `AbstractHttpCommand`.
- Commands must use `CliContext`, `CliHttpClient`, `CliOutputRenderer`, and `CliPathResolver` instead of ad-hoc clients or hardcoded home paths.
- `melonpaw` is the only supported CLI entry point.
