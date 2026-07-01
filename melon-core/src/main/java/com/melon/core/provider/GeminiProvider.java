package com.melon.core.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Google Gemini Provider configuration. Supports gemini-1.5-pro, gemini-1.5-flash and other Gemini models.
 *
 * <p>API key is read from the {@code GOOGLE_API_KEY} environment variable.
 * Base URL: {@code https://generativelanguage.googleapis.com}
 */
public class GeminiProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);

    public static final String PROVIDER_ID = "gemini";
    public static final String BASE_URL = "https://generativelanguage.googleapis.com";
    public static final String API_KEY_ENV_VAR = "GOOGLE_API_KEY";

    /** Supported models */
    private static final List<String> MODELS = List.of(
            "gemini-2.0-flash",
            "gemini-1.5-pro",
            "gemini-1.5-flash",
            "gemini-1.5-flash-8b",
            "gemini-1.0-pro"
    );

    private final String providerId;
    private final String baseUrl;
    private final List<String> models;
    private final String apiKeyEnvVar;

    public GeminiProvider() {
        this.providerId = PROVIDER_ID;
        this.baseUrl = BASE_URL;
        this.models = MODELS;
        this.apiKeyEnvVar = API_KEY_ENV_VAR;
    }

    /**
     * Tests the connection to Google Gemini by checking if the API key is available.
     *
     * @return true if the GOOGLE_API_KEY environment variable is set and non-blank
     */
    public boolean testConnection() {
        String apiKey = System.getenv(apiKeyEnvVar);
        boolean available = apiKey != null && !apiKey.isBlank();
        log.info("Provider {} connection test: {} (env var {} is {})",
                providerId, available ? "PASS" : "FAIL", apiKeyEnvVar, available ? "set" : "not set");
        return available;
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
     * Retrieves the API key from the environment variable.
     *
     * @return the API key, or null if not set
     */
    public String getApiKey() {
        return System.getenv(apiKeyEnvVar);
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
