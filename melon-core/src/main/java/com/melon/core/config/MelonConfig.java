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

    @JsonProperty("home_dir")
    private String homeDir;

    @JsonProperty("paths")
    private PathsConfig paths;

    @JsonProperty("state")
    private StateConfig state;

    public ServerConfig getServer() { return server; }
    public void setServer(ServerConfig server) { this.server = server; }

    public Map<String, AgentConfig> getAgents() { return agents; }
    public void setAgents(Map<String, AgentConfig> agents) { this.agents = agents; }

    public AgentConfig getAgent(String id) { return agents.get(id); }

    public String getHomeDir() { return homeDir; }
    public void setHomeDir(String homeDir) { this.homeDir = homeDir; }

    public PathsConfig getPaths() { return paths; }
    public void setPaths(PathsConfig paths) { this.paths = paths; }

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
        private String store;
        @JsonProperty("base_dir")
        private String baseDir;

        public String getStore() { return store; }
        public void setStore(String store) { this.store = store; }
        public String getBaseDir() { return baseDir; }
        public void setBaseDir(String baseDir) { this.baseDir = baseDir; }
    }

    public static class PathsConfig {
        @JsonProperty("workspace_root")
        private String workspaceRoot;

        @JsonProperty("skill_pool_dir")
        private String skillPoolDir;

        public String getWorkspaceRoot() { return workspaceRoot; }
        public void setWorkspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; }
        public String getSkillPoolDir() { return skillPoolDir; }
        public void setSkillPoolDir(String skillPoolDir) { this.skillPoolDir = skillPoolDir; }
    }
}
