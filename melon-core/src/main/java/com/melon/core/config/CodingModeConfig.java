/**
 * @author melon
 */
package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Coding Mode 配置. 对应 Python CodingModeConfig.
 */
public class CodingModeConfig {

    @JsonProperty("enabled")
    private boolean enabled = false;

    @JsonProperty("lsp_servers")
    private Map<String, String> lspServers = Map.of();

    @JsonProperty("ast_grep_path")
    private String astGrepPath = "ast-grep";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Map<String, String> getLspServers() { return lspServers; }
    public void setLspServers(Map<String, String> lspServers) { this.lspServers = lspServers; }

    public String getAstGrepPath() { return astGrepPath; }
    public void setAstGrepPath(String astGrepPath) { this.astGrepPath = astGrepPath; }
}
