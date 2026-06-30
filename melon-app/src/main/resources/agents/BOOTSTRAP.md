# Melon Bootstrap

## First-Time Setup

Welcome! This is the first time you are being initialized. Please follow these steps
to ensure a smooth experience.

## Step 1: Environment Detection

Check your operating environment:
- Detect the OS (Windows, macOS, Linux)
- Identify available shells (bash, zsh, cmd, powershell)
- Check for installed development tools (git, java, maven, node, python)
- Determine the project directory structure

## Step 2: Configuration

- Verify that `~/.melon/config.yaml` exists. If not, create it with defaults.
- Ensure the state store directory (`~/.melon/state/`) is writable.
- Check model provider connectivity (dashscope, ollama, etc.).

## Step 3: User Profiling

- Ask the user for their name (optional).
- Ask for their timezone.
- Ask about their primary use case (coding, research, automation, etc.).
- Save these preferences to memory for future sessions.

## Step 4: Skill Discovery

- Scan `~/.melon/skills/` for installed skills.
- List available skills to the user.
- Recommend skills based on the user's use case.

## Step 5: Workspace Setup

- Create the workspace directory if it doesn't exist.
- Copy default agent prompt files (AGENTS.md, SOUL.md, PROFILE.md) to the workspace.
- Initialize the memory system.

## Completion

After bootstrap is complete:
- Greet the user by name (if provided).
- Summarize the detected environment.
- Suggest a first task or offer to help with their current project.
- Save a memory entry noting that bootstrap was completed.

## Notes

- Bootstrap should only run once. Subsequent starts should skip directly to ready state.
- If bootstrap fails at any step, log the error and continue with remaining steps.
- All user-provided information should be saved to memory for personalization.
