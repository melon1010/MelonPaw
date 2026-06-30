/**
 * @author melon
 */
package com.melon.core.agent;

import java.util.*;

/**
 * 斜杠命令处理器. 对应 Python commands.py.
 * <p>
 * 解析用户输入中的斜杠命令, 返回结构化结果供上层执行.
 * 支持的命令:
 * <ul>
 *   <li>{@code /compact} - 触发上下文压缩</li>
 *   <li>{@code /new} - 开始新会话</li>
 *   <li>{@code /clear} - 清除对话历史</li>
 *   <li>{@code /model <name>} - 切换模型</li>
 *   <li>{@code /skills [list|add|remove]} - 管理技能</li>
 * </ul>
 * <p>
 * 本类仅负责解析和验证, 不执行实际副作用.
 * 上层 (Controller/Service) 根据 {@link CommandResult} 的 action 执行对应操作.
 */
public class CommandHandler {

    /**
     * 命令类型.
     */
    public enum CommandType {
        COMPACT,
        NEW,
        CLEAR,
        MODEL,
        SKILLS,
        HELP,
        UNKNOWN
    }

    /**
     * 命令执行结果.
     */
    public static class CommandResult {
        public enum Action {
            COMPACT_CONTEXT,
            NEW_SESSION,
            CLEAR_HISTORY,
            SWITCH_MODEL,
            LIST_SKILLS,
            ADD_SKILL,
            REMOVE_SKILL,
            SHOW_HELP,
            NO_OP
        }

        private final CommandType type;
        private final Action action;
        private final boolean success;
        private final String message;
        private final Map<String, Object> data;

        private CommandResult(CommandType type, Action action, boolean success,
                              String message, Map<String, Object> data) {
            this.type = type;
            this.action = action;
            this.success = success;
            this.message = message;
            this.data = data;
        }

        static CommandResult ok(CommandType type, Action action, String message, Map<String, Object> data) {
            return new CommandResult(type, action, true, message, data);
        }

        public static CommandResult error(CommandType type, String message) {
            return new CommandResult(type, Action.NO_OP, false, message, Map.of());
        }

        public CommandType getType() { return type; }
        public Action getAction() { return action; }
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public Map<String, Object> getData() { return data; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("action", action);
            m.put("success", success);
            m.put("message", message);
            if (data != null && !data.isEmpty()) {
                m.put("data", data);
            }
            return m;
        }
    }

    /**
     * 解析并处理用户输入.
     *
     * @param input 用户输入 (以 / 开头)
     * @return 命令处理结果
     */
    public CommandResult handle(String input) {
        if (input == null || !input.startsWith("/")) {
            return CommandResult.error(CommandType.UNKNOWN, "Not a command: " + input);
        }

        String trimmed = input.trim();
        String[] parts = trimmed.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        return switch (cmd) {
            case "/compact" -> handleCompact(args);
            case "/new" -> handleNew(args);
            case "/clear" -> handleClear(args);
            case "/model" -> handleModel(args);
            case "/skills" -> handleSkills(args);
            case "/help" -> handleHelp();
            default -> CommandResult.error(CommandType.UNKNOWN, "Unknown command: " + cmd);
        };
    }

    /**
     * /compact - 触发上下文压缩.
     */
    private CommandResult handleCompact(String args) {
        return CommandResult.ok(CommandType.COMPACT, CommandResult.Action.COMPACT_CONTEXT,
                "Context compaction triggered", Map.of());
    }

    /**
     * /new - 开始新会话.
     */
    private CommandResult handleNew(String args) {
        String sessionName = args.isBlank() ? null : args;
        Map<String, Object> data = new LinkedHashMap<>();
        if (sessionName != null) {
            data.put("session_name", sessionName);
        }
        return CommandResult.ok(CommandType.NEW, CommandResult.Action.NEW_SESSION,
                "New session requested", data);
    }

    /**
     * /clear - 清除对话历史.
     */
    private CommandResult handleClear(String args) {
        return CommandResult.ok(CommandType.CLEAR, CommandResult.Action.CLEAR_HISTORY,
                "Conversation history cleared", Map.of());
    }

    /**
     * /model <name> - 切换模型.
     */
    private CommandResult handleModel(String args) {
        if (args.isBlank()) {
            return CommandResult.error(CommandType.MODEL,
                    "Usage: /model <model_name> (e.g., /model dashscope:qwen-max)");
        }
        return CommandResult.ok(CommandType.MODEL, CommandResult.Action.SWITCH_MODEL,
                "Model switch requested: " + args,
                Map.of("model", args));
    }

    /**
     * /skills [list|add <name>|remove <name>] - 管理技能.
     */
    private CommandResult handleSkills(String args) {
        if (args.isBlank()) {
            // 默认列出技能
            return CommandResult.ok(CommandType.SKILLS, CommandResult.Action.LIST_SKILLS,
                    "Listing skills", Map.of());
        }

        String[] parts = args.split("\\s+", 2);
        String subCmd = parts[0].toLowerCase();

        return switch (subCmd) {
            case "list" -> CommandResult.ok(CommandType.SKILLS, CommandResult.Action.LIST_SKILLS,
                    "Listing skills", Map.of());
            case "add" -> {
                if (parts.length < 2 || parts[1].isBlank()) {
                    yield CommandResult.error(CommandType.SKILLS, "Usage: /skills add <skill_name>");
                }
                yield CommandResult.ok(CommandType.SKILLS, CommandResult.Action.ADD_SKILL,
                        "Add skill: " + parts[1],
                        Map.of("skill", parts[1]));
            }
            case "remove", "rm" -> {
                if (parts.length < 2 || parts[1].isBlank()) {
                    yield CommandResult.error(CommandType.SKILLS, "Usage: /skills remove <skill_name>");
                }
                yield CommandResult.ok(CommandType.SKILLS, CommandResult.Action.REMOVE_SKILL,
                        "Remove skill: " + parts[1],
                        Map.of("skill", parts[1]));
            }
            default -> CommandResult.error(CommandType.SKILLS,
                    "Unknown sub-command: " + subCmd + ". Use: list, add, remove");
        };
    }

    /**
     * /help - 显示可用命令.
     */
    private CommandResult handleHelp() {
        String helpText = """
                Available commands:
                  /compact          - Trigger context compaction
                  /new [name]       - Start a new session
                  /clear            - Clear conversation history
                  /model <name>     - Switch to a different model
                  /skills           - List skills
                  /skills add <n>   - Add a skill
                  /skills remove <n>- Remove a skill
                  /help             - Show this help""";
        return CommandResult.ok(CommandType.HELP, CommandResult.Action.SHOW_HELP,
                helpText, Map.of());
    }

    /**
     * 判断输入是否为斜杠命令.
     */
    public static boolean isCommand(String input) {
        return input != null && input.startsWith("/");
    }
}
