package com.melon.channels;

import java.util.List;
import java.util.Set;

public final class ChannelTypes {

    public static final List<String> BUILTIN = List.of(
            "console",
            "dingtalk",
            "feishu",
            "imessage",
            "discord",
            "telegram",
            "qq",
            "wechat",
            "wecom",
            "yuanbao",
            "matrix",
            "sip",
            "xiaoyi",
            "slack",
            "mqtt",
            "mattermost",
            "onebot",
            "voice"
    );

    public static final Set<String> SECRET_KEYS = Set.of(
            "token",
            "bot_token",
            "app_token",
            "access_token",
            "client_secret",
            "app_secret",
            "encrypt_key",
            "verification_token",
            "secret",
            "password",
            "sip_password",
            "sk",
            "dashscope_api_key",
            "auth_token",
            "twilio_auth_token",
            "livekit_api_secret",
            "bot_token_file"
    );

    private ChannelTypes() {
    }
}
