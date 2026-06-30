/**
 * @author melon
 */
package com.melon.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 工具审批中间件. 对应 Python ToolGuardMixin.
 * 实现 OFF/AUTO/SMART/STRICT 四级审批.
 */
public class ToolGuardMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolGuardMiddleware.class);
    private final String approvalLevel;

    private static final Set<String> HIGH_RISK_TOOLS = Set.of(
            "execute_shell_command", "write_file", "edit_file",
            "browser_use", "desktop_screenshot", "send_file_to_user");

    private static final Set<String> DANGEROUS_PATTERNS = Set.of(
            "rm -rf", "rmdir /s", "del /f", "format", "mkfs",
            "dd if=", "shutdown", "reboot", "chmod 777", "kill -9", "mv / ");

    private final ConcurrentHashMap<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();

    public ToolGuardMiddleware(String approvalLevel) {
        this.approvalLevel = approvalLevel != null ? approvalLevel : "AUTO";
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {

        if ("OFF".equals(approvalLevel)) return next.apply(input);

        if ("AUTO".equals(approvalLevel)) {
            for (var toolCall : input.toolCalls()) {
                log.debug("Tool guard [AUTO]: auto-approved tool={}", toolCall.getName());
                if (isDangerousToolCall(toolCall))
                    log.warn("Dangerous tool call [AUTO]: {} - {}", toolCall.getName(), toolCall.getInput());
            }
            return next.apply(input);
        }

        Set<String> toolsNeedingApproval = new java.util.HashSet<>();
        for (var toolCall : input.toolCalls()) {
            String toolName = toolCall.getName();
            boolean needsApproval = switch (approvalLevel) {
                case "STRICT" -> true;
                case "SMART" -> HIGH_RISK_TOOLS.contains(toolName) || isDangerousToolCall(toolCall);
                default -> false;
            };

            if (needsApproval) {
                toolsNeedingApproval.add(toolCall.getId());
                pendingApprovals.put(toolCall.getId(), new PendingApproval(
                        toolCall.getId(), toolName, toolCall.getInput().toString(),
                        agent.getName(), System.currentTimeMillis()));
                log.info("Tool guard [{}]: approval required for tool={}", approvalLevel, toolName);
                ctx.put("pending_approval_" + toolCall.getId(),
                        new PendingApprovalInfo(toolName, toolCall.getInput().toString(), approvalLevel));
            }
        }

        if (toolsNeedingApproval.isEmpty()) return next.apply(input);

        return Flux.fromIterable(input.toolCalls()).flatMap(toolCall -> {
            if (toolsNeedingApproval.contains(toolCall.getId())) {
                return Flux.just(createApprovalRequestEvent(toolCall));
            }
            return next.apply(new ActingInput(List.of(toolCall)));
        });
    }

    private AgentEvent createApprovalRequestEvent(ToolUseBlock toolCall) {
        return new CustomEvent("approval_required", Map.of(
                "approval_required", true,
                "approval_level", approvalLevel,
                "approval_id", toolCall.getId(),
                "toolCallId", toolCall.getId(),
                "toolName", toolCall.getName(),
                "content", "Approval required (" + approvalLevel + " mode). Tool: "
                        + toolCall.getName() + ", Args: " + toolCall.getInput()
        ));
    }

    private boolean isDangerousToolCall(ToolUseBlock toolCall) {
        String args = toolCall.getInput().toString().toLowerCase();
        return DANGEROUS_PATTERNS.stream().anyMatch(p -> args.contains(p.toLowerCase()));
    }

    public boolean handleApprovalResponse(String approvalId, boolean approved) {
        PendingApproval pending = pendingApprovals.get(approvalId);
        if (pending == null) return false;
        log.info("Approval {} {} for tool {}", approvalId, approved ? "granted" : "denied", pending.toolName());
        pendingApprovals.remove(approvalId);
        return true;
    }

    public ConcurrentHashMap<String, PendingApproval> getPendingApprovals() { return pendingApprovals; }

    public record PendingApproval(String id, String toolName, String arguments, String agentName, long timestamp) {}
    public record PendingApprovalInfo(String toolName, String arguments, String approvalLevel) {}
}
