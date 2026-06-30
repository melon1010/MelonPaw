/**
 * @author melon
 */
package com.melon.core.env;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 环境变量存储. 对应 Python env_store.py.
 * <p>
 * 持久化到 {@code ~/.melon/envs.json}, 同时通过 {@link System#setProperty} 注入到 JVM 系统属性.
 * 支持 CRUD 操作, 修改后自动持久化和注入.
 */
public class EnvStore {

    private static final Logger log = LoggerFactory.getLogger(EnvStore.class);
    private static final String CONFIG_DIR = ".melon";
    private static final String ENV_FILE = "envs.json";

    private final ObjectMapper objectMapper;
    private final Path envFilePath;
    private final Map<String, String> envs = new ConcurrentHashMap<>();

    public EnvStore() {
        this.objectMapper = new ObjectMapper();
        this.envFilePath = Path.of(System.getProperty("user.home"), CONFIG_DIR, ENV_FILE);
    }

    /**
     * 从文件加载环境变量.
     */
    public void load() {
        if (Files.exists(envFilePath)) {
            try {
                Map<String, String> loaded = objectMapper.readValue(
                        envFilePath.toFile(),
                        new TypeReference<Map<String, String>>() {});
                envs.clear();
                envs.putAll(loaded);
                log.info("Loaded {} env vars from {}", envs.size(), envFilePath);
            } catch (IOException e) {
                log.error("Failed to load envs from {}", envFilePath, e);
            }
        } else {
            log.info("Env file not found: {}, starting empty", envFilePath);
        }
        // 加载后注入系统属性
        injectAll();
    }

    /**
     * 保存环境变量到文件 (原子写入).
     */
    public void save() {
        try {
            Files.createDirectories(envFilePath.getParent());
            Path tmp = envFilePath.resolveSibling(ENV_FILE + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), envs);
            Files.move(tmp, envFilePath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Envs saved to {}", envFilePath);
        } catch (IOException e) {
            log.error("Failed to save envs to {}", envFilePath, e);
        }
    }

    /**
     * 设置环境变量: 持久化 + 注入系统属性.
     *
     * @param key   变量名
     * @param value 变量值
     */
    public void setEnv(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Env key cannot be null or blank");
        }
        envs.put(key, value);
        System.setProperty(key, value);
        save();
        log.info("Env set: {}={}", key, value != null ? "***" : "null");
    }

    /**
     * 获取环境变量.
     *
     * @param key 变量名
     * @return 变量值, 不存在则返回 null
     */
    public String getEnv(String key) {
        return envs.get(key);
    }

    /**
     * 移除环境变量: 持久化 + 清除系统属性.
     *
     * @param key 变量名
     * @return 被移除的值, 不存在则返回 null
     */
    public String removeEnv(String key) {
        String removed = envs.remove(key);
        if (removed != null) {
            System.clearProperty(key);
            save();
            log.info("Env removed: {}", key);
        }
        return removed;
    }

    /**
     * 列出所有环境变量.
     */
    public Map<String, String> listEnvs() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(envs));
    }

    /**
     * 获取所有变量名.
     */
    public Set<String> keySet() {
        return Collections.unmodifiableSet(envs.keySet());
    }

    /**
     * 将所有环境变量注入到 JVM 系统属性.
     * <p>
     * 启动时调用, 使配置的环境变量对整个 JVM 可见.
     */
    public void injectAll() {
        for (Map.Entry<String, String> entry : envs.entrySet()) {
            if (entry.getValue() != null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
        log.info("Injected {} env vars into system properties", envs.size());
    }

    /**
     * 获取环境变量文件路径.
     */
    public Path getEnvFilePath() {
        return envFilePath;
    }

    /**
     * 检查是否包含指定变量.
     */
    public boolean containsKey(String key) {
        return envs.containsKey(key);
    }

    /**
     * 变量数量.
     */
    public int size() {
        return envs.size();
    }
}
