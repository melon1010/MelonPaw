package com.melon.app.runner;

import com.melon.tools.shell.ExecuteShellCommandTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class ShellCancellationSelfCheck {

    private ShellCancellationSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        String sessionId = "shell-cancel-check";
        ExecuteShellCommandTool tool = new ExecuteShellCommandTool(null, 30.0, null);
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("call-shell").name("execute_shell_command").build())
                .runtimeContext(RuntimeContext.builder().sessionId(sessionId).build())
                .input(Map.of("command", "sleep 30", "timeout", 30))
                .build();

        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                tool.callAsync(param).block();
            } catch (Throwable t) {
                error.set(t);
            }
        }, "shell-cancel-self-check");
        worker.start();

        int cancelled = 0;
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && cancelled == 0) {
            Thread.sleep(100);
            cancelled = ExecuteShellCommandTool.cancelSession(sessionId);
        }
        if (cancelled == 0) {
            throw new AssertionError("shell process was not registered for cancellation");
        }
        worker.join(5000);
        if (worker.isAlive()) {
            throw new AssertionError("shell process did not stop after cancellation");
        }
        if (error.get() != null) {
            throw new AssertionError("shell cancellation should not surface as tool failure", error.get());
        }
    }
}
