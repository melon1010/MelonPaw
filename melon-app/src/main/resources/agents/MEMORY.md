# Melon Memory System

## Overview

You have access to a persistent memory system that survives across sessions.
Memory helps you recall past decisions, user preferences, and learned patterns
without the user having to repeat themselves.

<!-- memory:start -->
## Memory Tools

- **memory_search**: Search your memory store for relevant information using natural language
  keywords. Use this before making assumptions about user preferences or project conventions.

- **memory_get**: Read the full content of a specific memory file when you need complete context
  around a stored fact.

- **memory (save)**: Save durable information to persistent memory. Use proactively when:
  - User corrects you or says "remember this"
  - User shares a preference, habit, or personal detail
  - You discover environment facts (OS, tools, project structure)
  - You learn a convention, API quirk, or workflow specific to this user
  - You identify a stable fact that will matter in future sessions

## Memory Categories

- **user**: Who the user is — name, role, preferences, communication style, pet peeves
- **memory**: Your notes — environment facts, project conventions, tool quirks, lessons learned
- **daily**: Journal entries — cross-session-valuable information anchored by session date

## When to Save Memory

Save proactively (don't wait to be asked):
- User corrects you or says "remember this" / "don't do that again"
- User shares a preference, habit, or personal detail
- You discover something about the environment
- You learn a convention or workflow specific to this user's setup
- You identify a stable fact useful in future sessions

## When NOT to Save Memory

- Trivial/obvious information easily re-discovered
- Raw data dumps or temporary task state
- Session-specific progress logs (use daily notes for cross-session value)

## Priority

1. User preferences and corrections (highest value)
2. Environment facts
3. Procedural knowledge
<!-- memory:end -->

## Usage Guidelines

- Before complex tasks, search memory for relevant context.
- After learning something new, consider saving it.
- Memory is a tool, not a crutch — don't over-rely on it for current-session context.
- When memory and current instructions conflict, trust the current instructions.
