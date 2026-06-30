/**
 * @author melon
 */
package com.melon.app.runner;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import com.melon.core.agent.MultiAgentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 请求→Agent 调用编排. 对应 Python runner/runner.py 的 query_handler.
 * 区别: 不新建 Agent, 复用单例; AgentScope 自动管理 state load/save.
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    @Autowired
    private MultiAgentManager multiAgentManager;

    /**
     * 流式查询. 对应 Python _stream_printing_messages_interruptible.
     */
    public Flux<AgentEvent> stream(String agentId, List<Msg> msgs,
                                    String userId, String sessionId,
                                    Map<String, Object> envInfo) {
        log.info("Stream query: agent={}, user={}, session={}", agentId, userId, sessionId);

        HarnessAgent agent = multiAgentManager.getOrCreate(agentId);

        RuntimeContext ctx = buildContext(userId, sessionId, envInfo);

        return agent.streamEvents(msgs, ctx);
    }

    /**
     * 非流式查询.
     */
    public Mono<Msg> query(String agentId, List<Msg> msgs,
                            String userId, String sessionId,
                            Map<String, Object> envInfo) {
        log.info("Query: agent={}, user={}, session={}", agentId, userId, sessionId);

        HarnessAgent agent = multiAgentManager.getOrCreate(agentId);

        RuntimeContext ctx = buildContext(userId, sessionId, envInfo);

        return agent.call(msgs, ctx);
    }

    private RuntimeContext buildContext(String userId, String sessionId, Map<String, Object> envInfo) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .userId(userId != null ? userId : "default")
                .sessionId(sessionId != null ? sessionId : "default");

        if (envInfo != null) {
            if (envInfo.get("working_dir") != null) {
                builder.put("working_dir", envInfo.get("working_dir"));
            }
            if (envInfo.get("channel") != null) {
                builder.put("channel", envInfo.get("channel"));
            }
            if (envInfo.get("shell") != null) {
                builder.put("shell", envInfo.get("shell"));
            }
            if (envInfo.get("project_dir") != null) {
                builder.put("project_dir", envInfo.get("project_dir"));
            }
        }

        return builder.build();
    }
}
