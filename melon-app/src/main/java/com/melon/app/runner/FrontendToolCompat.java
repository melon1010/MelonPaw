package com.melon.app.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.core.util.JsonUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalizes AgentScope tool names/arguments to the shape the copied QwenPaw
 * frontend tool cards expect.
 */
public final class FrontendToolCompat {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private FrontendToolCompat() {
    }

    public static String displayToolName(String name) {
        if ("execute".equals(name)) return "execute_shell_command";
        if ("grep_files".equals(name)) return "grep_search";
        if ("glob_files".equals(name)) return "glob_search";
        return name;
    }

    public static Map<String, Object> parseArguments(String toolName, String raw) {
        String args = unwrapToolCall(toolName, raw);
        if (args.isBlank()) return Map.of();
        Map<String, Object> parsed = tryParseJson(args);
        if (parsed == null) parsed = bestEffortArguments(args);
        return normalizeArguments(toolName, parsed);
    }

    public static String argumentsJson(String toolName, String raw) {
        return JsonUtils.toJson(parseArguments(toolName, raw));
    }

    private static Map<String, Object> tryParseJson(String args) {
        try {
            return JSON.readValue(args, MAP_TYPE);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private static Map<String, Object> normalizeArguments(String toolName, Map<String, Object> args) {
        if (args.isEmpty()) return args;
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>(args);
        String displayName = displayToolName(toolName);
        if ("execute_shell_command".equals(displayName)) {
            copyIfPresent(normalized, "working_directory", "cwd");
        } else if (isFileTool(displayName)) {
            copyIfPresent(normalized, "path", "file_path");
            copyIfPresent(normalized, "file_path", "path");
        }
        return normalized;
    }

    private static boolean isFileTool(String displayName) {
        return "read_file".equals(displayName)
                || "write_file".equals(displayName)
                || "edit_file".equals(displayName)
                || "append_file".equals(displayName)
                || "view_image".equals(displayName)
                || "view_video".equals(displayName)
                || "send_file_to_user".equals(displayName);
    }

    private static void copyIfPresent(Map<String, Object> map, String from, String to) {
        if (!map.containsKey(to) && map.get(from) != null) {
            map.put(to, map.get(from));
        }
    }

    private static String unwrapToolCall(String toolName, String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        int marker = text.indexOf("[tool_call:");
        if (marker >= 0) {
            int start = text.indexOf('(', marker);
            int end = text.lastIndexOf(")]");
            if (start >= 0) {
                return (end > start ? text.substring(start + 1, end) : text.substring(start + 1)).trim();
            }
        }
        String actualName = actualToolName(toolName);
        String prefix = actualName + "(";
        if (!actualName.isBlank() && text.startsWith(prefix)) {
            int end = text.endsWith(")") ? text.length() - 1 : text.length();
            return text.substring(prefix.length(), end).trim();
        }
        return text;
    }

    private static String actualToolName(String toolName) {
        if ("execute_shell_command".equals(toolName)) return "execute";
        if ("grep_search".equals(toolName)) return "grep_files";
        if ("glob_search".equals(toolName)) return "glob_files";
        return toolName != null ? toolName : "";
    }

    private static Map<String, Object> bestEffortArguments(String text) {
        LinkedHashMap<String, Object> args = new LinkedHashMap<>();
        copyExtractedString(text, args, "command");
        copyExtractedString(text, args, "cmd");
        copyExtractedString(text, args, "working_directory");
        copyExtractedString(text, args, "cwd");
        copyExtractedString(text, args, "path");
        copyExtractedString(text, args, "file_path");
        copyExtractedString(text, args, "pattern");
        Long timeout = extractNumber(text, "timeout");
        if (timeout != null) args.put("timeout", timeout);
        return args;
    }

    private static void copyExtractedString(String text, Map<String, Object> args, String key) {
        String value = extractString(text, key);
        if (value != null) args.put(key, value);
    }

    private static String extractString(String text, String key) {
        String needle = "\"" + key + "\"";
        int keyIndex = text.indexOf(needle);
        if (keyIndex < 0) return null;
        int colon = text.indexOf(':', keyIndex + needle.length());
        if (colon < 0) return null;
        int quote = text.indexOf('"', colon + 1);
        if (quote < 0) return null;
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = quote + 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                value.append(switch (ch) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> ch;
                });
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                return value.toString();
            } else {
                value.append(ch);
            }
        }
        return value.isEmpty() ? null : value.toString();
    }

    private static Long extractNumber(String text, String key) {
        String needle = "\"" + key + "\"";
        int keyIndex = text.indexOf(needle);
        if (keyIndex < 0) return null;
        int colon = text.indexOf(':', keyIndex + needle.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        int end = start;
        while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
        if (end == start) return null;
        return Long.parseLong(text.substring(start, end));
    }
}
