/**
 * @author melon
 */
package com.melon.app.config;

import com.melon.core.config.ConfigManager;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Configuration for AgentScope state store.
 * Uses JSON file-based storage by default.
 * Corresponds to Python SafeJSONSession persistence.
 */
@Configuration
public class StateStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(StateStoreConfig.class);

    private final ConfigManager configManager;

    public StateStoreConfig(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @Bean
    public AgentStateStore agentStateStore() {
        String storeType = configManager.stateStoreType();
        Path stateDir = configManager.resolveStateDir();
        log.info("Initializing state store: type={}, dir={}", storeType, stateDir);

        return switch (storeType.toLowerCase()) {
            case "json", "json_file" -> new JsonFileAgentStateStore(stateDir);
            // case "redis" -> new RedisAgentStateStore(...);  // Future
            // case "mysql" -> new MysqlAgentStateStore(...);   // Future
            default -> {
                log.warn("Unknown store type '{}', falling back to JSON", storeType);
                yield new JsonFileAgentStateStore(stateDir);
            }
        };
    }
}
