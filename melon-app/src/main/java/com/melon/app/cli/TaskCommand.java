package com.melon.app.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.app.cli.context.CliContext;
import com.melon.app.cli.context.CliOptionResolver;
import com.melon.app.cli.http.CliHttpClient;
import com.melon.app.cli.http.CliHttpResponse;
import com.melon.app.cli.output.CliOutputRenderer;
import com.melon.app.cli.spec.CliCommandSpecs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "task", description = "Run a single task headlessly", mixinStandardHelpOptions = true)
public class TaskCommand implements Callable<Integer> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Spec CommandSpec commandSpec;
    @Parameters(arity = "0..*", paramLabel = "TEXT") List<String> text;
    @Option(names = {"--instruction", "--message"}) String instruction;
    @Option(names = "--agent", defaultValue = "default") String agent;
    @Option(names = "--user-id", defaultValue = "default") String userId;
    @Option(names = "--session-id", defaultValue = "default") String sessionId;
    @Option(names = "--task-timeout", defaultValue = "300") double taskTimeout;
    @Option(names = "--poll-interval", defaultValue = "1") double pollInterval;
    @Option(names = "--no-wait") boolean noWait;

    @Override
    public Integer call() {
        String message = instruction != null ? instruction : String.join(" ", text == null ? List.of() : text);
        return submitAndPoll(commandSpec, agent, userId, sessionId, message, taskTimeout, pollInterval, noWait);
    }

    static int submitAndPoll(CommandSpec commandSpec, String agent, String userId, String sessionId, String message,
                             double taskTimeout, double pollInterval, boolean noWait) {
        return submitAndPoll(commandSpec, agent, userId, sessionId, message, taskTimeout, pollInterval, noWait, Map.of());
    }

    static int submitAndPoll(CommandSpec commandSpec, String agent, String userId, String sessionId, String message,
                             double taskTimeout, double pollInterval, boolean noWait,
                             Map<String, Object> requestContext) {
        if (message == null || message.isBlank()) {
            System.err.println("Task text is required.");
            return 1;
        }
        CliContext context = CliContext.from(CliOptionResolver.from(commandSpec));
        CliHttpClient client = new CliHttpClient();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", message);
        body.put("user_id", userId == null || userId.isBlank() ? "default" : userId);
        body.put("session_id", sessionId == null || sessionId.isBlank() ? "default" : sessionId);
        body.put("timeout", taskTimeout);
        if (requestContext != null && !requestContext.isEmpty()) {
            body.put("request_context", requestContext);
        }
        Map<String, String> headers = Map.of("X-Agent-Id", agent == null || agent.isBlank() ? "default" : agent);
        try {
            CliHttpResponse submit = client.execute(context, CliCommandSpecs.CONSOLE_TASK_SUBMIT, Map.of(), body, headers);
            if (!CliCommandSpecs.CONSOLE_TASK_SUBMIT.successStatuses().contains(submit.statusCode())) {
                return client.printResponse(context, CliCommandSpecs.CONSOLE_TASK_SUBMIT, submit);
            }
            Map<?, ?> payload = MAPPER.readValue(submit.body(), Map.class);
            String taskId = String.valueOf(payload.get("task_id"));
            if (noWait) {
                System.out.println(taskId);
                return 0;
            }
            long deadline = System.nanoTime() + (long) (Math.max(1, taskTimeout) * 1_000_000_000L);
            while (System.nanoTime() < deadline) {
                CliHttpResponse status = client.execute(context, CliCommandSpecs.CONSOLE_TASK_STATUS, Map.of("taskId", taskId), null, headers);
                if (!CliCommandSpecs.CONSOLE_TASK_STATUS.successStatuses().contains(status.statusCode())) {
                    return client.printResponse(context, CliCommandSpecs.CONSOLE_TASK_STATUS, status);
                }
                Map<?, ?> state = MAPPER.readValue(status.body(), Map.class);
                if ("finished".equals(String.valueOf(state.get("status")))) {
                    return printFinished(context, state);
                }
                Thread.sleep((long) (Math.max(0.1, pollInterval) * 1000));
            }
            System.err.println("Task timed out before completion.");
            return 1;
        } catch (Exception e) {
            System.err.println("Task failed: " + e.getMessage());
            return 1;
        }
    }

    private static int printFinished(CliContext context, Map<?, ?> state) throws Exception {
        Object result = state.get("result");
        if (!(result instanceof Map<?, ?> map)) {
            System.out.println(new CliOutputRenderer().render(state, context.outputFormat()));
            return 0;
        }
        if (!"completed".equals(String.valueOf(map.get("status")))) {
            System.err.println(new CliOutputRenderer().render(map, context.outputFormat()));
            return 1;
        }
        String text = firstText(map.get("output"));
        System.out.println(text.isBlank() ? new CliOutputRenderer().render(map, context.outputFormat()) : text);
        return 0;
    }

    private static String firstText(Object output) {
        if (!(output instanceof List<?> list)) return "";
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> message)) continue;
            Object content = message.get("content");
            if (!(content instanceof List<?> blocks)) continue;
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> raw && "text".equals(String.valueOf(raw.get("type")))) {
                    Object value = raw.get("text");
                    return value == null ? "" : String.valueOf(value);
                }
            }
        }
        return "";
    }
}
