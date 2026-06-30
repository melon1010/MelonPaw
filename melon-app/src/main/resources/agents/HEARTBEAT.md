# Melon Heartbeat

<!-- trigger:active -->

## Heartbeat Instructions

You are being triggered by a heartbeat signal. This means the system is checking in with you
to see if there is proactive work to be done.

## Check List

1. **Pending tasks**: Review any tasks that were started but not completed in previous
   interactions. If there are incomplete tasks, continue working on them.

2. **Follow-up actions**: Check if there are actions you promised to do "later" or "after
   confirmation" that can now be executed.

3. **Error recovery**: If the last interaction ended with an error, investigate whether the
   issue has resolved itself or if a retry is appropriate.

4. **Idle behavior**: If there is genuinely nothing pending, do nothing. Do not invent work.
   Simply acknowledge the heartbeat and remain ready.

## Response Format

When responding to a heartbeat:
- Briefly state what you found (pending tasks, errors, or nothing).
- If taking action, describe what you are doing.
- If idle, respond with: "Heartbeat received. No pending tasks. Standing by."

## Important

- Do NOT start new user-facing tasks without user initiation.
- Do NOT send messages to users unless there is a critical error requiring attention.
- Heartbeat is for background maintenance and continuation, not for initiating new work.
- Respect rate limits and resource constraints.
