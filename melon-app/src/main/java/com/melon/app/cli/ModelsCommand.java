package com.melon.app.cli;

import com.melon.app.cli.spec.AbstractHttpCommand;
import com.melon.app.cli.spec.CliCommandSpecs;
import com.melon.app.cli.spec.CliKeyValueParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "models", description = "Manage model providers", mixinStandardHelpOptions = true,
        subcommands = {
                ModelsCommand.ListModels.class,
                ModelsCommand.ActiveModels.class,
                ModelsCommand.ConfigModels.class,
                ModelsCommand.ConfigKey.class,
                ModelsCommand.SetLlm.class,
                ModelsCommand.TestProvider.class,
                ModelsCommand.TestModel.class,
                ModelsCommand.Discover.class,
                ModelsCommand.ProbeMultimodal.class,
                ModelsCommand.AddProvider.class,
                ModelsCommand.RemoveProvider.class,
                ModelsCommand.ProviderOAuthStart.class,
                ModelsCommand.ProviderOAuthStatus.class,
                ModelsCommand.CustomProviders.class,
                ModelsCommand.OpenRouterSeries.class,
                ModelsCommand.OpenRouterDiscover.class,
                ModelsCommand.OpenRouterFilter.class,
                ModelsCommand.AddModel.class,
                ModelsCommand.ConfigModel.class,
                ModelsCommand.RemoveModel.class,
                ModelsCommand.LocalConfig.class,
                ModelsCommand.LocalServer.class,
                ModelsCommand.DownloadModel.class,
                ModelsCommand.DownloadStatus.class,
                ModelsCommand.CancelDownload.class,
                ModelsCommand.ListLocal.class,
                ModelsCommand.RemoveLocal.class
        })
public class ModelsCommand extends AbstractHttpCommand implements Callable<Integer> {

    @Override
    public Integer call() { return execute(CliCommandSpecs.MODELS_LIST); }

    @Command(name = "list", description = "List models", mixinStandardHelpOptions = true)
    static class ListModels extends AbstractHttpCommand implements Callable<Integer> {
        @Override
        public Integer call() { return execute(CliCommandSpecs.MODELS_LIST); }
    }

    @Command(name = "active", description = "Get active model settings", mixinStandardHelpOptions = true)
    static class ActiveModels extends AbstractHttpCommand implements Callable<Integer> {
        @Override
        public Integer call() { return execute(CliCommandSpecs.MODELS_ACTIVE_GET); }
    }

    @Command(name = "config", description = "Get or update provider config", mixinStandardHelpOptions = true)
    static class ConfigModels extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER") String providerId;
        @Option(names = "--set", description = "Provider config key=value") List<String> fields;

        @Override
        public Integer call() {
            if (fields == null || fields.isEmpty()) {
                return execute(CliCommandSpecs.MODELS_PROVIDER_CONFIG, Map.of("providerId", providerId), null);
            }
            return execute(CliCommandSpecs.MODELS_UPDATE_PROVIDER_CONFIG, Map.of("providerId", providerId),
                    CliKeyValueParser.parsePairs(fields));
        }
    }

    @Command(name = "config-key", description = "Set provider API key", mixinStandardHelpOptions = true)
    static class ConfigKey extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER") String providerId;
        @Parameters(index = "1", paramLabel = "API_KEY") String apiKey;
        @Override
        public Integer call() {
            return execute(CliCommandSpecs.MODELS_UPDATE_PROVIDER_CONFIG, Map.of("providerId", providerId), Map.of("api_key", apiKey));
        }
    }

    @Command(name = "set-llm", description = "Set active LLM", mixinStandardHelpOptions = true)
    static class SetLlm extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "MODEL", description = "provider:model or model id") String model;
        @Option(names = "--agent", defaultValue = "default") String agent;
        @Option(names = "--scope", defaultValue = "agent") String scope;
        @Override
        public Integer call() {
            return execute(CliCommandSpecs.MODELS_ACTIVE_SET, Map.of(),
                    Map.of("active_model", model, "agent_id", agent, "scope", scope));
        }
    }

    @Command(name = "test-provider", description = "Test provider connectivity", mixinStandardHelpOptions = true)
    static class TestProvider extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER") String providerId;
        @Option(names = "--set", description = "Temporary config key=value") List<String> fields;
        @Override
        public Integer call() {
            return execute(CliCommandSpecs.MODELS_TEST_PROVIDER, Map.of("providerId", providerId), CliKeyValueParser.parsePairs(fields));
        }
    }

    @Command(name = "test-model", description = "Test model connectivity", mixinStandardHelpOptions = true)
    static class TestModel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER") String providerId;
        @Parameters(index = "1", paramLabel = "MODEL") String modelId;
        @Override
        public Integer call() {
            return execute(CliCommandSpecs.MODELS_TEST_MODEL, Map.of("providerId", providerId), Map.of("model_id", modelId));
        }
    }

    @Command(name = "discover", description = "Discover provider models", mixinStandardHelpOptions = true)
    static class Discover extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER") String providerId;
        @Option(names = "--set") List<String> fields;
        public Integer call() {
            return CliHttpSupport.request(commandSpec, "POST", "/api/models/" + CliHttpSupport.url(providerId) + "/discover",
                    CliKeyValueParser.parsePairs(fields));
        }
    }

    @Command(name = "probe-multimodal", description = "Probe model multimodal support", mixinStandardHelpOptions = true)
    static class ProbeMultimodal extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER") String providerId;
        @Parameters(index = "1", paramLabel = "MODEL") String modelId;
        public Integer call() {
            return CliHttpSupport.request(commandSpec, "POST", "/api/models/" + CliHttpSupport.url(providerId)
                    + "/models/" + CliHttpSupport.url(modelId) + "/probe-multimodal", Map.of());
        }
    }

    @Command(name = "add-provider", description = "Create a custom provider", mixinStandardHelpOptions = true)
    static class AddProvider extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER") String providerId;
        @Option(names = "--name") String name;
        @Option(names = {"--provider-base-url", "--default-base-url"}) String baseUrl;
        @Option(names = "--api-key") String apiKey;
        @Option(names = "--set", description = "Provider config key=value") List<String> fields;
        @Override
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>(CliKeyValueParser.parsePairs(fields));
            body.put("id", providerId);
            body.put("provider_id", providerId);
            if (name != null) body.put("name", name);
            if (baseUrl != null) body.put("base_url", baseUrl);
            if (apiKey != null) body.put("api_key", apiKey);
            return execute(CliCommandSpecs.MODELS_CUSTOM_PROVIDER_CREATE, Map.of(), body);
        }
    }

    @Command(name = "remove-provider", description = "Delete a custom provider", mixinStandardHelpOptions = true)
    static class RemoveProvider extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER") String providerId;
        @Override
        public Integer call() { return execute(CliCommandSpecs.MODELS_CUSTOM_PROVIDER_DELETE, Map.of("providerId", providerId), null); }
    }

    @Command(name = "provider-oauth-start", description = "Start provider OAuth", mixinStandardHelpOptions = true)
    static class ProviderOAuthStart extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER") String providerId;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/providers/" + CliHttpSupport.url(providerId) + "/oauth/start", Map.of()); }
    }

    @Command(name = "provider-oauth-status", description = "Show provider OAuth status", mixinStandardHelpOptions = true)
    static class ProviderOAuthStatus extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER") String providerId;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/providers/" + CliHttpSupport.url(providerId) + "/oauth/status", null); }
    }

    @Command(name = "custom-providers", description = "List custom providers", mixinStandardHelpOptions = true)
    static class CustomProviders extends AbstractHttpCommand implements Callable<Integer> {
        @Override
        public Integer call() { return execute(CliCommandSpecs.MODELS_CUSTOM_PROVIDERS_LIST); }
    }

    @Command(name = "openrouter-series", mixinStandardHelpOptions = true)
    static class OpenRouterSeries extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/models/openrouter/series", null); }
    }

    @Command(name = "openrouter-discover", mixinStandardHelpOptions = true)
    static class OpenRouterDiscover extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--set") List<String> fields;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/models/openrouter/discover-extended", CliKeyValueParser.parsePairs(fields)); }
    }

    @Command(name = "openrouter-filter", mixinStandardHelpOptions = true)
    static class OpenRouterFilter extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--set") List<String> fields;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/models/openrouter/models/filter", CliKeyValueParser.parsePairs(fields)); }
    }

    @Command(name = "add-model", description = "Add a model to a provider", mixinStandardHelpOptions = true)
    static class AddModel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER") String providerId;
        @Parameters(index = "1", paramLabel = "MODEL") String modelId;
        @Option(names = "--name") String name;
        @Option(names = "--set", description = "Model field key=value") List<String> fields;
        @Override
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>(CliKeyValueParser.parsePairs(fields));
            body.put("id", modelId);
            body.put("model_id", modelId);
            if (name != null) body.put("name", name);
            return execute(CliCommandSpecs.MODELS_ADD_MODEL, Map.of("providerId", providerId), body);
        }
    }

    @Command(name = "config-model", description = "Configure a provider model", mixinStandardHelpOptions = true)
    static class ConfigModel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER") String providerId;
        @Parameters(index = "1", paramLabel = "MODEL") String modelId;
        @Option(names = "--set", required = true, description = "Model config key=value") List<String> fields;
        @Override
        public Integer call() {
            return execute(CliCommandSpecs.MODELS_CONFIG_MODEL, Map.of("providerId", providerId, "modelId", modelId),
                    CliKeyValueParser.parsePairs(fields));
        }
    }

    @Command(name = "remove-model", description = "Remove a provider model", mixinStandardHelpOptions = true)
    static class RemoveModel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER") String providerId;
        @Parameters(index = "1", paramLabel = "MODEL") String modelId;
        @Override
        public Integer call() { return execute(CliCommandSpecs.MODELS_REMOVE_MODEL, Map.of("providerId", providerId, "modelId", modelId), null); }
    }

    @Command(name = "local-config", description = "Get or update local model config", mixinStandardHelpOptions = true)
    static class LocalConfig extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--set") List<String> fields;
        public Integer call() {
            return fields == null || fields.isEmpty()
                    ? CliHttpSupport.request(commandSpec, "GET", "/api/local-models/config", null)
                    : CliHttpSupport.request(commandSpec, "PUT", "/api/local-models/config", CliKeyValueParser.parsePairs(fields));
        }
    }

    @Command(name = "local-server", description = "Manage local model server", mixinStandardHelpOptions = true)
    static class LocalServer extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--start") boolean start;
        @Option(names = "--stop") boolean stop;
        @Option(names = "--set") List<String> fields;
        public Integer call() {
            if (stop) return CliHttpSupport.request(commandSpec, "DELETE", "/api/local-models/server", null);
            if (start) return CliHttpSupport.request(commandSpec, "POST", "/api/local-models/server", CliKeyValueParser.parsePairs(fields));
            return CliHttpSupport.request(commandSpec, "GET", "/api/local-models/server", null);
        }
    }

    @Command(name = "download", description = "Download a local model through Ollama", mixinStandardHelpOptions = true)
    static class DownloadModel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "MODEL") String model;
        @Option(names = "--source", defaultValue = "auto") String source;
        public Integer call() {
            return execute(CliCommandSpecs.MODELS_LOCAL_DOWNLOAD, Map.of(), Map.of("model_name", model, "source", source));
        }
    }

    @Command(name = "download-status", description = "Show local model download status", mixinStandardHelpOptions = true)
    static class DownloadStatus extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return execute(CliCommandSpecs.MODELS_LOCAL_DOWNLOAD_STATUS); }
    }

    @Command(name = "cancel-download", description = "Cancel local model download", mixinStandardHelpOptions = true)
    static class CancelDownload extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return execute(CliCommandSpecs.MODELS_LOCAL_DOWNLOAD_CANCEL); }
    }

    @Command(name = "local", description = "List local models", mixinStandardHelpOptions = true)
    static class ListLocal extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return execute(CliCommandSpecs.MODELS_LOCAL_LIST); }
    }

    @Command(name = "remove-local", description = "Remove a local model", mixinStandardHelpOptions = true)
    static class RemoveLocal extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "MODEL") String model;
        public Integer call() { return execute(CliCommandSpecs.MODELS_LOCAL_REMOVE, Map.of("modelId", model), null); }
    }
}
