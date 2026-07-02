package com.melon.core.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ValueUtils {

    private ValueUtils() {
    }

    public static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    public static String trimmedStringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    public static boolean booleanValue(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            String text = trimmedStringValue(item, "");
            if (!text.isBlank()) seen.add(text);
        }
        return new ArrayList<>(seen);
    }
}
