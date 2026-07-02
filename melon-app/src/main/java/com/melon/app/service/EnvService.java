package com.melon.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.core.config.ConfigManager;
import com.melon.core.env.EnvBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 环境变量服务. 读写 envs.json, 管理用户自定义环境变量.
 * Corresponds to Python env_service.py.
 */
@Service
public class EnvService {

    private static final Logger log = LoggerFactory.getLogger(EnvService.class);

    private final Path envsFile;
    private final ObjectMapper mapper = new ObjectMapper();

    public EnvService(ConfigManager configManager) {
        this.envsFile = configManager.resolveHomeDir().resolve("envs.json");
        try {
            Files.createDirectories(envsFile.getParent());
            if (!Files.exists(envsFile)) {
                saveEnvs(new LinkedHashMap<>());
            }
            EnvBridge.injectAll(listEnvs());
        } catch (IOException e) {
            log.warn("Failed to initialize envs file: {}", envsFile);
        }
    }

    /**
     * 加载所有环境变量.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> listEnvs() {
        if (!Files.exists(envsFile)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> raw = mapper.readValue(envsFile.toFile(),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            Map<String, String> result = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            return result;
        } catch (IOException e) {
            log.error("Failed to load envs from {}", envsFile, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 获取单个环境变量.
     */
    public String getEnv(String key) {
        return listEnvs().get(key);
    }

    /**
     * 设置环境变量 (新增或更新).
     */
    public void setEnv(String key, String value) {
        Map<String, String> envs = new LinkedHashMap<>(listEnvs());
        envs.put(key, value);
        saveEnvs(envs);
        log.info("Env set: {}={}", key, "***");
    }

    /**
     * 删除环境变量.
     */
    public boolean deleteEnv(String key) {
        Map<String, String> envs = new LinkedHashMap<>(listEnvs());
        if (envs.remove(key) != null) {
            saveEnvs(envs);
            System.clearProperty(key);
            log.info("Env deleted: {}", key);
            return true;
        }
        return false;
    }

    /**
     * 保存环境变量到 JSON 文件 (原子写入).
     */
    private void saveEnvs(Map<String, String> envs) {
        try {
            Files.createDirectories(envsFile.getParent());
            Path tmp = envsFile.resolveSibling("envs.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), envs);
            Files.move(tmp, envsFile, StandardCopyOption.REPLACE_EXISTING);
            EnvBridge.injectAll(envs);
        } catch (IOException e) {
            log.error("Failed to save envs to {}", envsFile, e);
        }
    }

    /**
     * 获取 envs.json 文件路径.
     */
    public Path getEnvsFilePath() {
        return envsFile;
    }
}
