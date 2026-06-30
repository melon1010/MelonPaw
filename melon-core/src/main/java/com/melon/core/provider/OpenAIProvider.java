/**
 * @author melon
 */
package com.melon.core.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * OpenAI Provider configuration. Supports GPT-4, GPT-4o, GPT-3.5-turbo and other OpenAI models.
 *
 * <p>API key is read from the {@code OPENAI_API_KEY} environment variable.
 * Base URL: {@code https://api.openai.com/v1}
 */
public class OpenAIProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAIProvider.class);

    public static final String PROVIDER_ID = "openai";
    public static final String BASE_URL = "https://api.openai.com/v1";
    public static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";

    /** Supported models */
    private static final List<String> MODELS = List.of(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4-turbo",
            "gpt-4",
            "gpt-3.5-turbo",
            "o1",
            "o1-mini",
            "o3-mini"
    );

    private final String providerId;
    private final String baseUrl;
    private final List<String> models;
    private final String apiKeyEnvVar;

    public OpenAIProvider() {
        this.providerId = PROVIDER_ID;
        this.baseUrl = BASE_URL;
        this.models = MODELS;
        this.apiKeyEnvVar = API_KEY_ENV_VAR;
    }

    /**
     * Tests the connection to OpenAI by checking if the API key is available.
     *
     * @return true if the OPENAI_API_KEY environment variable is set and non-blank
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
