package com.melon.channels;

import com.fasterxml.jackson.core.type.TypeReference;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.melon.core.util.ValueUtils.stringValue;

public class ChannelAccessControlStore {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ConfigManager configManager;

    public ChannelAccessControlStore(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public Map<String, Object> all(String agentId) {
        Map<String, Object> data = load(agentId);
        Map<String, Object> result = new LinkedHashMap<>();
        data.forEach((channel, raw) -> {
            if (raw instanceof Map<?, ?> acl && hasAclData(acl)) {
                result.put(channel, copy(acl));
            }
        });
        for (String channel : accessControlEnabledChannels(agentId)) {
            result.putIfAbsent(channel, channelAcl(data, channel));
        }
        return result;
    }

    public Map<String, Object> channel(String agentId, String channel) {
        Map<String, Object> data = load(agentId);
        Object raw = data.get(channel);
        return raw instanceof Map<?, ?> map ? copy(map) : emptyAcl();
    }

    public List<Map<String, Object>> pendingAll(String agentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : all(agentId).entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> raw
                    && raw.get("pending") instanceof List<?> pending) {
                for (Object item : pending) {
                    if (item instanceof Map<?, ?> map) result.add(copy(map));
                }
            }
        }
        return result;
    }

    public Map<String, Object> addUsers(String agentId, String listName, List<Map<String, Object>> entries) {
        Map<String, Object> data = load(agentId);
        for (Map<String, Object> entry : entries != null ? entries : List.<Map<String, Object>>of()) {
            String channel = stringValue(entry.get("channel"), "console");
            String userId = stringValue(entry.get("user_id"));
            if (userId.isBlank()) continue;
            Map<String, Object> acl = acl(data, channel);
            Map<String, Object> users = users(acl, listName);
            users.put(userId, userInfo(entry));
            removePending(acl, channel, userId);
        }
        save(agentId, data);
        return data;
    }

    public Map<String, Object> removeUsers(String agentId, String listName, List<Map<String, Object>> entries) {
        Map<String, Object> data = load(agentId);
        for (Map<String, Object> entry : entries != null ? entries : List.<Map<String, Object>>of()) {
            String channel = stringValue(entry.get("channel"), "console");
            String userId = stringValue(entry.get("user_id"));
            if (userId.isBlank()) continue;
            users(acl(data, channel), listName).remove(userId);
        }
        save(agentId, data);
        return data;
    }

    public Map<String, Object> updateRemark(String agentId, String channel, String userId, String remark) {
        Map<String, Object> data = load(agentId);
        Map<String, Object> acl = acl(data, channel);
        for (String list : List.of("whitelist", "blacklist")) {
            Object raw = users(acl, list).get(userId);
            if (raw instanceof Map<?, ?> map) {
                Map<String, Object> info = copy(map);
                info.put("remark", remark != null ? remark : "");
                users(acl, list).put(userId, info);
            }
        }
        updatePendingField(acl, userId, "remark", remark);
        save(agentId, data);
        return data;
    }

    public Map<String, Object> updateUsername(String agentId, String channel, String userId, String username) {
        Map<String, Object> data = load(agentId);
        Map<String, Object> acl = acl(data, channel);
        for (String list : List.of("whitelist", "blacklist")) {
            Object raw = users(acl, list).get(userId);
            if (raw instanceof Map<?, ?> map) {
                Map<String, Object> info = copy(map);
                info.put("username", username != null ? username : "");
                users(acl, list).put(userId, info);
            }
        }
        updatePendingField(acl, userId, "username", username);
        save(agentId, data);
        return data;
    }

    public void addPending(String agentId, ChannelInboundMessage message) {
        Map<String, Object> data = load(agentId);
        Map<String, Object> acl = acl(data, message.getChannel());
        List<Object> pending = pending(acl);
        boolean exists = pending.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(item -> message.getUserId().equals(stringValue(item.get("user_id"))));
        if (!exists) {
            pending.add(new LinkedHashMap<>(Map.of(
                    "user_id", message.getUserId(),
                    "channel", message.getChannel(),
                    "timestamp", System.currentTimeMillis() / 1000.0,
                    "first_message", message.getContent(),
                    "remark", "",
                    "username", ""
            )));
        }
        save(agentId, data);
    }

    public boolean allowed(String agentId, String channel, String userId, Map<String, Object> channelMeta, Map<String, Object> channelConfig) {
        boolean group = isGroup(channelMeta);
        if (group && Boolean.TRUE.equals(channelConfig.get("group_disabled"))) return false;
        if (!group && Boolean.TRUE.equals(channelConfig.get("dm_disabled"))) return false;
        Map<String, Object> acl = channel(agentId, channel);
        if (users(acl, "blacklist").containsKey(userId)) return false;
        boolean gate = group
                ? Boolean.TRUE.equals(channelConfig.get("access_control_group"))
                : Boolean.TRUE.equals(channelConfig.get("access_control_dm"));
        if (!gate) {
            gate = group
                    ? "allowlist".equals(stringValue(channelConfig.get("group_policy")))
                    : "allowlist".equals(stringValue(channelConfig.get("dm_policy")));
        }
        return !gate || users(acl, "whitelist").containsKey(userId);
    }

    public boolean allowed(String agentId, String channel, String userId, Map<String, Object> channelConfig) {
        return allowed(agentId, channel, userId, Map.of(), channelConfig);
    }

    private boolean isGroup(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) return false;
        if (Boolean.TRUE.equals(meta.get("is_group"))) return true;
        for (String key : List.of("group_id", "chatid", "wecom_chatid", "group_openid")) {
            if (!stringValue(meta.get(key)).isBlank()) return true;
        }
        String chatType = stringValue(meta.get("chat_type"), stringValue(meta.get("wecom_chat_type"), stringValue(meta.get("feishu_chat_type"))));
        return "group".equalsIgnoreCase(chatType);
    }

    public Map<String, Object> pendingAction(String agentId, String action, List<Map<String, Object>> entries) {
        Map<String, Object> data = load(agentId);
        for (Map<String, Object> entry : entries != null ? entries : List.<Map<String, Object>>of()) {
            String channel = stringValue(entry.get("channel"), "console");
            String userId = stringValue(entry.get("user_id"));
            if (userId.isBlank()) continue;
            Map<String, Object> acl = acl(data, channel);
            if ("approve".equals(action)) {
                users(acl, "whitelist").put(userId, pendingUserInfo(acl, channel, userId, entry));
                users(acl, "blacklist").remove(userId);
            } else if ("deny".equals(action)) {
                users(acl, "blacklist").put(userId, pendingUserInfo(acl, channel, userId, entry));
                users(acl, "whitelist").remove(userId);
            }
            removePending(acl, channel, userId);
        }
        save(agentId, data);
        return all(agentId);
    }

    private Map<String, Object> load(String agentId) {
        Map<String, Object> data = JsonUtils.load(file(agentId), MAP_TYPE);
        return data != null ? data : new LinkedHashMap<>();
    }

    private void save(String agentId, Map<String, Object> data) {
        JsonUtils.save(file(agentId), data);
    }

    private Path file(String agentId) {
        return configManager.resolveWorkspaceDir(agentId).resolve("channels").resolve("access_control.json");
    }

    private Map<String, Object> acl(Map<String, Object> data, String channel) {
        Object raw = data.get(channel);
        Map<String, Object> acl = raw instanceof Map<?, ?> map ? copy(map) : emptyAcl();
        data.put(channel, acl);
        return acl;
    }

    private Map<String, Object> channelAcl(Map<String, Object> data, String channel) {
        Object raw = data.get(channel);
        return raw instanceof Map<?, ?> map ? copy(map) : emptyAcl();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> users(Map<String, Object> acl, String listName) {
        Object raw = acl.get(listName);
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            acl.put(listName, copy);
            return copy;
        }
        Map<String, Object> users = new LinkedHashMap<>();
        acl.put(listName, users);
        return users;
    }

    @SuppressWarnings("unchecked")
    private List<Object> pending(Map<String, Object> acl) {
        Object raw = acl.get("pending");
        if (raw instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            acl.put("pending", copy);
            return copy;
        }
        List<Object> pending = new ArrayList<>();
        acl.put("pending", pending);
        return pending;
    }

    private void removePending(Map<String, Object> acl, String channel, String userId) {
        pending(acl).removeIf(item -> item instanceof Map<?, ?> map
                && userId.equals(stringValue(map.get("user_id")))
                && channel.equals(stringValue(map.get("channel"), channel)));
    }

    private Map<String, Object> pendingUserInfo(Map<String, Object> acl, String channel, String userId, Map<String, Object> entry) {
        Map<String, Object> info = userInfo(entry);
        for (Object item : pending(acl)) {
            if (item instanceof Map<?, ?> map
                    && userId.equals(stringValue(map.get("user_id")))
                    && channel.equals(stringValue(map.get("channel"), channel))) {
                if (stringValue(info.get("remark")).isBlank()) {
                    info.put("remark", stringValue(map.get("remark")));
                }
                if (stringValue(info.get("username")).isBlank()) {
                    info.put("username", stringValue(map.get("username")));
                }
                break;
            }
        }
        return info;
    }

    private void updatePendingField(Map<String, Object> acl, String userId, String field, String value) {
        List<Object> items = pending(acl);
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (item instanceof Map<?, ?> raw && userId.equals(stringValue(raw.get("user_id")))) {
                Map<String, Object> updated = copy(raw);
                updated.put(field, value != null ? value : "");
                items.set(i, updated);
            }
        }
    }

    private Map<String, Object> userInfo(Map<String, Object> entry) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("remark", stringValue(entry.get("remark")));
        info.put("username", stringValue(entry.get("username")));
        return info;
    }

    private boolean hasAclData(Map<?, ?> acl) {
        return nonEmptyMap(acl.get("whitelist"))
                || nonEmptyMap(acl.get("blacklist"))
                || nonEmptyList(acl.get("pending"));
    }

    private boolean nonEmptyMap(Object value) {
        return value instanceof Map<?, ?> map && !map.isEmpty();
    }

    private boolean nonEmptyList(Object value) {
        return value instanceof List<?> list && !list.isEmpty();
    }

    private List<String> accessControlEnabledChannels(String agentId) {
        AgentConfig agent = configManager.getConfig().getAgents().get(agentId);
        if (agent == null || agent.getChannels() == null) return List.of();
        List<String> result = new ArrayList<>();
        agent.getChannels().forEach((channel, config) -> {
            if (config == null) return;
            boolean enabled = Boolean.TRUE.equals(config.get("access_control_dm"))
                    || Boolean.TRUE.equals(config.get("access_control_group"))
                    || "allowlist".equals(stringValue(config.get("dm_policy")))
                    || "allowlist".equals(stringValue(config.get("group_policy")));
            if (enabled) result.add(channel);
        });
        return result;
    }

    private Map<String, Object> emptyAcl() {
        Map<String, Object> acl = new LinkedHashMap<>();
        acl.put("whitelist", new LinkedHashMap<>());
        acl.put("blacklist", new LinkedHashMap<>());
        acl.put("pending", new ArrayList<>());
        return acl;
    }

    private Map<String, Object> copy(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
