package com.melon.plugin.api;

/**
 * Melon 插件 SPI 接口。
 * 第三方插件实现此接口，通过 Java SPI (ServiceLoader) 机制加载。
 */
public interface MelonPlugin {
    /**
     * 插件唯一标识
     */
    String getId();
    
    /**
     * 插件显示名称
     */
    String getDisplayName();
    
    /**
     * 插件版本
     */
    String getVersion();
    
    /**
     * 初始化插件
     * @param context 插件上下文，提供对 Melon 核心能力的访问
     */
    void init(PluginContext context);
    
    /**
     * 销毁插件，释放资源
     */
    void destroy();
}
