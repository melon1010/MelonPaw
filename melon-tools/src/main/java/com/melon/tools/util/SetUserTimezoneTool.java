package com.melon.tools.util;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.time.ZoneId;

/**
 * 设置用户时区工具. 对应 Python get_current_time.py:set_user_timezone.
 */
public class SetUserTimezoneTool {

    @Tool(name = "set_user_timezone", description = "Set the user's timezone for time display", readOnly = true)
    public String setUserTimezone(
            @ToolParam(name = "timezone_name", description = "IANA timezone name (e.g. Asia/Shanghai)") String timezoneName
    ) {
        try {
            ZoneId zone = ZoneId.of(timezoneName);
            return "Timezone set to: " + zone.getId();
        } catch (Exception e) {
            return "Error: Invalid timezone '" + timezoneName + "'. Use IANA name like 'Asia/Shanghai'.";
        }
    }
}
