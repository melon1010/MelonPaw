package com.melon.plugin.api;

import java.nio.file.Path;
import java.util.Map;

/**
 * 插件运行时上下文。
 * 在 init() 时传入，插件可通过此接口访问 Melon 核心能力。
 */
public interface PluginContext {
    /**
     * 获取 Melon 工作区路径
     */
    Path getWorkspaceDir();
    
    /**
     * 获取配置值
     */
    <T> T getConfig(String key, Class<T> type);
    
    /**
     * 获取所有配置
     */
    Map<String, Object> getConfig();
    
    /**
     * 获取插件数据目录（每个插件独立）
     */
    Path getPluginDataDir();
}
