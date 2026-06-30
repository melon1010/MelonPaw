/**
 * @author melon
 */
package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

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
}
