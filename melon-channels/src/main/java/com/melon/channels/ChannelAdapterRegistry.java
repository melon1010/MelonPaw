package com.melon.channels;

import java.util.LinkedHashMap;
import java.util.Map;

public class ChannelAdapterRegistry implements AutoCloseable {

    private final Map<String, ChannelAdapter> adapters = new LinkedHashMap<>();

    public ChannelAdapterRegistry() {
        register(new BasicChannelAdapter("console", true, false, true, false));
        register(new HttpChannelAdapter("dingtalk", true, true, true));
        register(new HttpChannelAdapter("feishu", true, true, true));
        register(new IMessageChannelAdapter());
        register(new HttpChannelAdapter("discord", false, true, false));
        register(new HttpChannelAdapter("telegram", false, true, false));
        register(new QQChannelAdapter());
        register(new WeChatChannelAdapter());
        register(new WeComChannelAdapter());
        register(new YuanbaoChannelAdapter());
        register(new HttpChannelAdapter("matrix", false, false, false));
        register(new SipChannelAdapter());
        register(new XiaoYiChannelAdapter());
        register(new HttpChannelAdapter("slack", false, true, false));
        register(new MqttChannelAdapter());
        register(new HttpChannelAdapter("mattermost", false, false, false));
        register(new OneBotChannelAdapter());
        register(new VoiceChannelAdapter());
    }

    public void register(ChannelAdapter adapter) {
        adapters.put(adapter.type(), adapter);
    }

    public ChannelAdapter get(String type) {
        return adapters.computeIfAbsent(type, key -> new HttpChannelAdapter(key, false, false, true));
    }

    public Map<String, ChannelAdapter> all() {
        return new LinkedHashMap<>(adapters);
    }

    @Override
    public void close() {
        adapters.values().forEach(ChannelAdapter::close);
    }
}
