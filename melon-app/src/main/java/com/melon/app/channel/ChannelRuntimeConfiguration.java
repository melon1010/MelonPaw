package com.melon.app.channel;

import com.melon.channels.ChannelAccessControlStore;
import com.melon.channels.ChannelAdapterRegistry;
import com.melon.channels.ChannelConfigService;
import com.melon.channels.ChannelManager;
import com.melon.channels.ChannelQueueManager;
import com.melon.core.config.ConfigManager;
import org.springframework.core.Ordered;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

@Configuration
public class ChannelRuntimeConfiguration {

    @Bean
    public ChannelAdapterRegistry channelAdapterRegistry() {
        return new ChannelAdapterRegistry();
    }

    @Bean
    public ChannelConfigService channelConfigService(ConfigManager configManager,
                                                     ChannelAdapterRegistry registry,
                                                     ChannelAccessControlStore accessControlStore) {
        return new ChannelConfigService(configManager, registry, accessControlStore);
    }

    @Bean
    public ChannelAccessControlStore channelAccessControlStore(ConfigManager configManager) {
        return new ChannelAccessControlStore(configManager);
    }

    @Bean(destroyMethod = "close")
    public ChannelQueueManager channelQueueManager() {
        return new ChannelQueueManager();
    }

    @Bean
    public ChannelManager qwenPawChannelManager(ChannelAdapterRegistry registry,
                                                ChannelConfigService configService,
                                                ChannelAccessControlStore accessControlStore,
                                                ChannelQueueManager queueManager,
                                                ChannelRuntimeProcessor processor) {
        return new ChannelManager(registry, configService, accessControlStore, queueManager, processor::process);
    }

    @Bean
    public OneBotWebSocketHandler oneBotWebSocketHandler(ChannelAdapterRegistry registry, ChannelManager channelManager) {
        return new OneBotWebSocketHandler(registry, channelManager);
    }

    @Bean
    public HandlerMapping oneBotWebSocketMapping(OneBotWebSocketHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        mapping.setUrlMap(Map.of("/api/channels/onebot/websocket", handler));
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
