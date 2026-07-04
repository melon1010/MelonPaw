package com.melon.channels;

import java.util.LinkedHashMap;
import java.util.Map;

public class ChannelAddress {

    private String kind = "dm";
    private String id = "";
    private Map<String, Object> extra = new LinkedHashMap<>();

    public ChannelAddress() {
    }

    public ChannelAddress(String kind, String id, Map<String, Object> extra) {
        this.kind = kind;
        this.id = id;
        this.extra = extra != null ? new LinkedHashMap<>(extra) : new LinkedHashMap<>();
    }

    public String toHandle() {
        Object handle = extra.get("to_handle");
        return handle != null ? String.valueOf(handle) : kind + ":" + id;
    }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> extra) { this.extra = extra != null ? new LinkedHashMap<>(extra) : new LinkedHashMap<>(); }
}
