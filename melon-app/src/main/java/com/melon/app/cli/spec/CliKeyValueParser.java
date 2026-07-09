package com.melon.app.cli.spec;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CliKeyValueParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CliKeyValueParser() {
    }

    public static Map<String, Object> parsePairs(List<String> pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (pairs == null) {
            return values;
        }
        for (String pair : pairs) {
            int split = pair == null ? -1 : pair.indexOf('=');
            if (split <= 0) {
                throw new IllegalArgumentException("Expected key=value, got: " + pair);
            }
            values.put(pair.substring(0, split), parseValue(pair.substring(split + 1)));
        }
        return values;
    }

    public static Object parseValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("null")) {
            return null;
        }
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(trimmed);
        }
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                return MAPPER.readValue(trimmed, Object.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON value: " + value, e);
            }
        }
        try {
            if (trimmed.matches("-?\\d+")) {
                long number = Long.parseLong(trimmed);
                if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
                    return (int) number;
                }
                return number;
            }
            if (trimmed.matches("-?\\d+\\.\\d+")) {
                return Double.parseDouble(trimmed);
            }
        } catch (NumberFormatException ignored) {
        }
        return value;
    }

    public static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }
}
