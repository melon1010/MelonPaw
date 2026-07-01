/**
 * @author melon
 */
package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个内置工具配置. 对应 Python BuiltinToolConfig.
 */
public class BuiltinToolConfig {

    public static final boolean DEFAULT_ENABLED = true;

    @JsonProperty("name")
    private String name;

    @JsonProperty("enabled")
    private boolean enabled = true;

    @JsonProperty("description")
    private String description = "";

    @JsonProperty("display_to_user")
    private boolean displayToUser = true;

    @JsonProperty("async_execution")
    private boolean asyncExecution = false;

    @JsonProperty("icon")
    private String icon;

    @JsonProperty("requires_config")
    private boolean requiresConfig = false;

    @JsonProperty("config_fields")
    private List<Map<String, Object>> configFields = List.of();

    @JsonProperty("config")
    private Map<String, Object> config = new LinkedHashMap<>();

    public BuiltinToolConfig() {}

    public BuiltinToolConfig(String name, boolean enabled, String description) {
        this.name = name;
        this.enabled = enabled;
        this.description = description;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isDisplayToUser() { return displayToUser; }
    public void setDisplayToUser(boolean displayToUser) { this.displayToUser = displayToUser; }

    public boolean isAsyncExecution() { return asyncExecution; }
    public void setAsyncExecution(boolean asyncExecution) { this.asyncExecution = asyncExecution; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public boolean isRequiresConfig() { return requiresConfig; }
    public void setRequiresConfig(boolean requiresConfig) { this.requiresConfig = requiresConfig; }

    public List<Map<String, Object>> getConfigFields() { return configFields; }
    public void setConfigFields(List<Map<String, Object>> configFields) {
        this.configFields = configFields == null ? List.of() : configFields;
    }

    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) {
        this.config = config == null ? new LinkedHashMap<>() : config;
    }
}
