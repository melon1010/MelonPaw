/**
 * @author melon
 */
package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Melon 全局配置根. 对应 Python config.py 的 MelonConfig.
 */
public class MelonConfig {

    @JsonProperty("server")
    private ServerConfig server = new ServerConfig();

    @JsonProperty("agents")
    private Map<String, AgentConfig> agents = Map.of("default", new AgentConfig());

    @JsonProperty("state")
    private StateConfig state = new StateConfig();

    public ServerConfig getServer() { return server; }
    public void setServer(ServerConfig server) { this.server = server; }

    public Map<String, AgentConfig> getAgents() { return agents; }
    public void setAgents(Map<String, AgentConfig> agents) { this.agents = agents; }

    public AgentConfig getAgent(String id) { return agents.get(id); }

    public StateConfig getState() { return state; }
    public void setState(StateConfig state) { this.state = state; }

    public static class ServerConfig {
        @JsonProperty("host")
        private String host = "127.0.0.1";
        @JsonProperty("port")
        private int port = 8088;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public static class StateConfig {
        @JsonProperty("store")
        private String store = "json_file";
        @JsonProperty("base_dir")
        private String baseDir = "~/.melon/state";

        public String getStore() { return store; }
        public void setStore(String store) { this.store = store; }
        public String getBaseDir() { return baseDir; }
        public void setBaseDir(String baseDir) { this.baseDir = baseDir; }
    }
}
