package com.melon.core.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JSON 工具. 安全加载/保存 JSON 文件, 原子写入.
 * Corresponds to Python json_utils helpers.
 */
public final class JsonUtils {

    private static final Logger log = LoggerFactory.getLogger(JsonUtils.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    private JsonUtils() {}

    /**
     * 获取共享 ObjectMapper 实例.
     */
    public static ObjectMapper getMapper() {
        return mapper;
    }

    /**
     * 从 JSON 文件加载并反序列化为指定类型.
     * 如文件不存在返回 null.
     */
    public static <T> T load(Path jsonPath, Class<T> type) {
        if (!Files.exists(jsonPath)) {
            return null;
        }
        try {
            return mapper.readValue(jsonPath.toFile(), type);
        } catch (IOException e) {
            log.error("Failed to load JSON from {}", jsonPath, e);
            return null;
        }
    }

    /**
     * 从 JSON 文件加载并反序列化为 TypeReference (支持泛型).
     * 如文件不存在返回 null.
     */
    public static <T> T load(Path jsonPath, TypeReference<T> typeRef) {
        if (!Files.exists(jsonPath)) {
            return null;
        }
        try {
            return mapper.readValue(jsonPath.toFile(), typeRef);
        } catch (IOException e) {
            log.error("Failed to load JSON from {}", jsonPath, e);
            return null;
        }
    }

    /**
     * 原子保存对象为 JSON 文件.
     * 先写入临时文件, 再原子移动.
     */
    public static void save(Path jsonPath, Object value) {
        try {
            Files.createDirectories(jsonPath.getParent());
            Path tmp = jsonPath.resolveSibling(jsonPath.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            Files.move(tmp, jsonPath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("JSON saved to {}", jsonPath);
        } catch (IOException e) {
            log.error("Failed to save JSON to {}", jsonPath, e);
        }
    }

    /**
     * 将对象序列化为 JSON 字符串.
     */
    public static String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            log.error("Failed to serialize object to JSON", e);
            return "{}";
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型.
     * 失败时返回 null.
     */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (IOException e) {
            log.error("Failed to deserialize JSON: {}", json, e);
            return null;
        }
    }

    /**
     * 从 JSON 文件加载为 Map.
     * 如文件不存在返回空 Map.
     */
    public static java.util.Map<String, Object> loadAsMap(Path jsonPath) {
        if (!Files.exists(jsonPath)) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            return mapper.readValue(jsonPath.toFile(),
                    new TypeReference<java.util.LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
            log.error("Failed to load JSON as map from {}", jsonPath, e);
            return new java.util.LinkedHashMap<>();
        }
    }
}
