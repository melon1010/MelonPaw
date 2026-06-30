/**
 * @author melon
 */
package com.melon.core.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;

/**
 * Ollama Provider configuration. Supports local models like llama3, qwen2, etc.
 *
 * <p>Ollama runs locally and does not require an API key.
 * Base URL: {@code http://localhost:11434}
 */
public class OllamaProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);

    public static final String PROVIDER_ID = "ollama";
    public static final String BASE_URL = "http://localhost:11434";
    public static final String API_KEY_ENV_VAR = null; // No API key needed for local Ollama

    /** Supported models */
    private static final List<String> MODELS = List.of(
            "llama3.1",
            "llama3",
            "qwen2.5",
            "qwen2",
            "deepseek-r1",
            "mistral",
            "phi-3",
            "gemma2",
            "codellama"
    );

    private final String providerId;
    private final String baseUrl;
    private final List<String> models;
    private final String apiKeyEnvVar;

    public OllamaProvider() {
        this.providerId = PROVIDER_ID;
        this.baseUrl = BASE_URL;
        this.models = MODELS;
        this.apiKeyEnvVar = API_KEY_ENV_VAR;
    }

    /**
     * Tests the connection to the local Ollama server by making an HTTP request.
     * Ollama does not require an API key since it runs locally.
     *
     * @return true if the Ollama server is reachable
     */
    public boolean testConnection() {
        try {
            URI uri = URI.create(baseUrl + "/api/tags");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            int responseCode = conn.getResponseCode();
            conn.disconnect();
            boolean available = responseCode == 200;
            log.info("Provider {} connection test: {} (HTTP {})", providerId,
                    available ? "PASS" : "FAIL", responseCode);
            return available;
        } catch (Exception e) {
            log.info("Provider {} connection test: FAIL ({}: {})", providerId,
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    public String getProviderId() {
        return providerId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public List<String> getModels() {
        return models;
    }

    public String getApiKeyEnvVar() {
        return apiKeyEnvVar;
    }

    /**
     * Always returns null since Ollama does not require an API key.
     *
     * @return null
     */
    public String getApiKey() {
        return null;
    }

    /**
     * Checks if a model is supported by this provider.
     *
     * @param model the model name to check
     * @return true if the model is in the supported models list
     */
    public boolean supportsModel(String model) {
        return model != null && MODELS.contains(model);
    }
}
