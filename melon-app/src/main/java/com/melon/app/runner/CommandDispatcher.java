package com.melon.app.runner;

import com.melon.core.agent.CommandHandler;
import com.melon.core.agent.CommandHandler.CommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 命令分发器. 对应 Python app/runner/command_dispatch.py.
 * <p>
 * 检测用户消息中的 /命令, 分发给 {@link CommandHandler} 处理.
 * 返回命令结果或 null (非命令消息).
 */
@Component
public class CommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);

    private final CommandHandler commandHandler;

    public CommandDispatcher() {
        this.commandHandler = new CommandHandler();
    }

    /**
     * 检测并分发命令.
     * <p>
     * 如果用户消息以 / 开头, 则解析为命令并返回 {@link CommandResult}.
     * 否则返回 null, 表示这是一条普通消息.
     *
     * @param userInput 用户输入
     * @return 命令结果, 或 null 如果不是命令
     */
    public CommandResult dispatch(String userInput) {
        if (!isCommand(userInput)) {
            return null;
        }

        log.info("Dispatching command: {}", userInput.trim().split("\\s+")[0]);

        try {
            CommandResult result = commandHandler.handle(userInput);
            if (!result.isSuccess()) {
                log.warn("Command failed: {}", result.getMessage());
            }
            return result;
        } catch (Exception e) {
            log.error("Error dispatching command: {}", userInput, e);
            return CommandResult.error(
                    CommandHandler.CommandType.UNKNOWN,
                    "Command execution error: " + e.getMessage()
            );
        }
    }

    /**
     * 判断输入是否为命令 (以 / 开头).
     */
    public static boolean isCommand(String input) {
        return CommandHandler.isCommand(input);
    }
}
