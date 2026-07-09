package com.melon.app.cli.spec;

public final class CliCommandSpecs {

    private CliCommandSpecs() {
    }

    public static final CliCommandSpec AGENTS_LIST = CliCommandSpec.builder("agents.list")
            .get("/api/agents")
            .description("List agents")
            .build();

    public static final CliCommandSpec AGENTS_GET = CliCommandSpec.builder("agents.get")
            .get("/api/agents/{id}")
            .description("Get an agent")
            .build();

    public static final CliCommandSpec AGENTS_CREATE = CliCommandSpec.builder("agents.create")
            .post("/api/agents")
            .description("Create an agent")
            .build();

    public static final CliCommandSpec AGENTS_UPDATE = CliCommandSpec.builder("agents.update")
            .put("/api/agents/{id}")
            .description("Update an agent")
            .build();

    public static final CliCommandSpec AGENTS_DELETE = CliCommandSpec.builder("agents.delete")
            .delete("/api/agents/{id}")
            .description("Delete an agent")
            .build();

    public static final CliCommandSpec AGENTS_TOGGLE = CliCommandSpec.builder("agents.toggle")
            .patch("/api/agents/{id}/toggle")
            .description("Enable or disable an agent")
            .build();

    public static final CliCommandSpec AGENTS_ORDER = CliCommandSpec.builder("agents.order")
            .put("/api/agents/order")
            .description("Set agent order")
            .build();

    public static final CliCommandSpec MODELS_LIST = CliCommandSpec.builder("models.list")
            .get("/api/models")
            .description("List model providers and models")
            .build();

    public static final CliCommandSpec MODELS_PROVIDER_CONFIG = CliCommandSpec.builder("models.provider-config")
            .get("/api/models/{providerId}/config")
            .description("Get provider config")
            .build();

    public static final CliCommandSpec MODELS_UPDATE_PROVIDER_CONFIG = CliCommandSpec.builder("models.update-provider-config")
            .put("/api/models/{providerId}/config")
            .description("Update provider config")
            .build();

    public static final CliCommandSpec MODELS_TEST_PROVIDER = CliCommandSpec.builder("models.test-provider")
            .post("/api/models/{providerId}/test")
            .description("Test provider connectivity")
            .build();

    public static final CliCommandSpec MODELS_TEST_MODEL = CliCommandSpec.builder("models.test-model")
            .post("/api/models/{providerId}/models/test")
            .description("Test model connectivity")
            .build();

    public static final CliCommandSpec MODELS_ADD_MODEL = CliCommandSpec.builder("models.add-model")
            .post("/api/models/{providerId}/models")
            .description("Add a model to a provider")
            .build();

    public static final CliCommandSpec MODELS_REMOVE_MODEL = CliCommandSpec.builder("models.remove-model")
            .delete("/api/models/{providerId}/models/{modelId}")
            .description("Remove a model from a provider")
            .build();

    public static final CliCommandSpec MODELS_CONFIG_MODEL = CliCommandSpec.builder("models.config-model")
            .put("/api/models/{providerId}/models/{modelId}/config")
            .description("Configure a model")
            .build();

    public static final CliCommandSpec MODELS_ACTIVE_GET = CliCommandSpec.builder("models.active-get")
            .get("/api/models/active")
            .description("Get active model settings")
            .build();

    public static final CliCommandSpec MODELS_ACTIVE_SET = CliCommandSpec.builder("models.active-set")
            .put("/api/models/active")
            .description("Set active model")
            .build();

    public static final CliCommandSpec MODELS_CUSTOM_PROVIDERS_LIST = CliCommandSpec.builder("models.custom-providers-list")
            .get("/api/models/custom-providers")
            .description("List custom providers")
            .build();

    public static final CliCommandSpec MODELS_CUSTOM_PROVIDER_CREATE = CliCommandSpec.builder("models.custom-provider-create")
            .post("/api/models/custom-providers")
            .description("Create custom provider")
            .build();

    public static final CliCommandSpec MODELS_CUSTOM_PROVIDER_DELETE = CliCommandSpec.builder("models.custom-provider-delete")
            .delete("/api/models/custom-providers/{providerId}")
            .description("Delete custom provider")
            .build();

    public static final CliCommandSpec MODELS_LOCAL_LIST = CliCommandSpec.builder("models.local-list")
            .get("/api/local-models/models")
            .description("List local models")
            .build();

    public static final CliCommandSpec MODELS_LOCAL_DOWNLOAD = CliCommandSpec.builder("models.local-download")
            .post("/api/local-models/models/download")
            .description("Download local model")
            .build();

    public static final CliCommandSpec MODELS_LOCAL_DOWNLOAD_STATUS = CliCommandSpec.builder("models.local-download-status")
            .get("/api/local-models/models/download")
            .description("Get local model download status")
            .build();

    public static final CliCommandSpec MODELS_LOCAL_DOWNLOAD_CANCEL = CliCommandSpec.builder("models.local-download-cancel")
            .delete("/api/local-models/models/download")
            .description("Cancel local model download")
            .build();

    public static final CliCommandSpec MODELS_LOCAL_REMOVE = CliCommandSpec.builder("models.local-remove")
            .delete("/api/local-models/models/{modelId}")
            .description("Remove local model")
            .build();

    public static final CliCommandSpec SKILLS_LIST = CliCommandSpec.builder("skills.list")
            .get("/api/skills")
            .description("List skills")
            .build();

    public static final CliCommandSpec SKILLS_REFRESH = CliCommandSpec.builder("skills.refresh")
            .post("/api/skills/refresh")
            .description("Refresh skills")
            .build();

    public static final CliCommandSpec SKILLS_GET = CliCommandSpec.builder("skills.get")
            .get("/api/skills/{name}")
            .description("Get skill content")
            .build();

    public static final CliCommandSpec SKILLS_CREATE = CliCommandSpec.builder("skills.create")
            .post("/api/skills")
            .description("Create a skill")
            .build();

    public static final CliCommandSpec SKILLS_SAVE = CliCommandSpec.builder("skills.save")
            .put("/api/skills/save")
            .description("Save a skill")
            .build();

    public static final CliCommandSpec SKILLS_ENABLE = CliCommandSpec.builder("skills.enable")
            .post("/api/skills/{name}/enable")
            .description("Enable a skill")
            .build();

    public static final CliCommandSpec SKILLS_DISABLE = CliCommandSpec.builder("skills.disable")
            .post("/api/skills/{name}/disable")
            .description("Disable a skill")
            .build();

    public static final CliCommandSpec SKILLS_DELETE = CliCommandSpec.builder("skills.delete")
            .delete("/api/skills/{name}")
            .description("Delete a skill")
            .build();

    public static final CliCommandSpec SKILLS_CONFIG_GET = CliCommandSpec.builder("skills.config-get")
            .get("/api/skills/{name}/config")
            .description("Get skill config")
            .build();

    public static final CliCommandSpec SKILLS_CONFIG_SET = CliCommandSpec.builder("skills.config-set")
            .put("/api/skills/{name}/config")
            .description("Set skill config")
            .build();

    public static final CliCommandSpec SKILLS_CONFIG_DELETE = CliCommandSpec.builder("skills.config-delete")
            .delete("/api/skills/{name}/config")
            .description("Delete skill config")
            .build();

    public static final CliCommandSpec SKILLS_TAGS_SET = CliCommandSpec.builder("skills.tags-set")
            .put("/api/skills/{name}/tags")
            .description("Set skill tags")
            .build();

    public static final CliCommandSpec SKILLS_CHANNELS_SET = CliCommandSpec.builder("skills.channels-set")
            .put("/api/skills/{name}/channels")
            .description("Set skill channels")
            .build();

    public static final CliCommandSpec SKILLS_INSTALL_START = CliCommandSpec.builder("skills.install-start")
            .post("/api/skills/hub/install/start")
            .description("Start skill install")
            .build();

    public static final CliCommandSpec SKILLS_INSTALL_STATUS = CliCommandSpec.builder("skills.install-status")
            .get("/api/skills/hub/install/status/{taskId}")
            .description("Get skill install status")
            .build();

    public static final CliCommandSpec SKILLS_IMPORT_BUILTIN = CliCommandSpec.builder("skills.import-builtin")
            .post("/api/skills/pool/import-builtin")
            .description("Import built-in skills")
            .build();

    public static final CliCommandSpec CHATS_LIST = CliCommandSpec.builder("chats.list")
            .get("/api/chats")
            .description("List chats")
            .build();

    public static final CliCommandSpec CHANNELS_LIST = CliCommandSpec.builder("channels.list")
            .get("/api/config/channels")
            .description("List channels")
            .build();

    public static final CliCommandSpec CHANNELS_TYPES = CliCommandSpec.builder("channels.types")
            .get("/api/config/channels/types")
            .description("List channel types")
            .build();

    public static final CliCommandSpec CHANNELS_TYPES_META = CliCommandSpec.builder("channels.types-meta")
            .get("/api/config/channels/types/meta")
            .description("List channel type metadata")
            .build();

    public static final CliCommandSpec CHANNELS_GET = CliCommandSpec.builder("channels.get")
            .get("/api/config/channels/{channel}")
            .description("Get channel config")
            .build();

    public static final CliCommandSpec CHANNELS_CONFIG = CliCommandSpec.builder("channels.config")
            .put("/api/config/channels/{channel}")
            .description("Update channel config")
            .build();

    public static final CliCommandSpec CHANNELS_START = CliCommandSpec.builder("channels.start")
            .post("/api/config/channels/{channel}/start")
            .description("Start channel")
            .build();

    public static final CliCommandSpec CHANNELS_STOP = CliCommandSpec.builder("channels.stop")
            .post("/api/config/channels/{channel}/stop")
            .description("Stop channel")
            .build();

    public static final CliCommandSpec CHANNELS_RESTART = CliCommandSpec.builder("channels.restart")
            .post("/api/config/channels/{channel}/restart")
            .description("Restart channel")
            .build();

    public static final CliCommandSpec CHANNELS_HEALTH = CliCommandSpec.builder("channels.health")
            .get("/api/config/channels/{channel}/health")
            .description("Get channel health")
            .build();

    public static final CliCommandSpec CHANNELS_HEALTH_ALL = CliCommandSpec.builder("channels.health-all")
            .get("/api/config/channels/health")
            .description("Get all channel health")
            .build();

    public static final CliCommandSpec CHANNELS_QRCODE = CliCommandSpec.builder("channels.qrcode")
            .get("/api/config/channels/{channel}/qrcode")
            .description("Get channel QR code")
            .build();

    public static final CliCommandSpec CHANNELS_QRCODE_STATUS = CliCommandSpec.builder("channels.qrcode-status")
            .get("/api/config/channels/{channel}/qrcode/status")
            .description("Get channel QR code status")
            .build();

    public static final CliCommandSpec CHANNELS_ACCESS_CONTROL = CliCommandSpec.builder("channels.access-control")
            .get("/api/access-control")
            .description("List channel access control")
            .build();

    public static final CliCommandSpec CHANNELS_ACCESS_CONTROL_CHANNEL = CliCommandSpec.builder("channels.access-control-channel")
            .get("/api/access-control/{channel}")
            .description("Get channel access control")
            .build();

    public static final CliCommandSpec CHANNELS_WEBHOOK = CliCommandSpec.builder("channels.webhook")
            .post("/api/channels/{channel}/webhook")
            .description("Send channel webhook payload")
            .build();

    public static final CliCommandSpec CRON_LIST = CliCommandSpec.builder("cron.list")
            .get("/api/cron/jobs")
            .description("List cron jobs")
            .build();

    public static final CliCommandSpec CRON_GET = CliCommandSpec.builder("cron.get")
            .get("/api/cron/jobs/{id}")
            .description("Get a cron job")
            .build();

    public static final CliCommandSpec CRON_CREATE = CliCommandSpec.builder("cron.create")
            .post("/api/cron/jobs")
            .description("Create a cron job")
            .build();

    public static final CliCommandSpec CRON_UPDATE = CliCommandSpec.builder("cron.update")
            .put("/api/cron/jobs/{id}")
            .description("Update a cron job")
            .build();

    public static final CliCommandSpec CRON_DELETE = CliCommandSpec.builder("cron.delete")
            .delete("/api/cron/jobs/{id}")
            .description("Delete a cron job")
            .build();

    public static final CliCommandSpec CRON_PAUSE = CliCommandSpec.builder("cron.pause")
            .post("/api/cron/jobs/{id}/pause")
            .description("Pause a cron job")
            .build();

    public static final CliCommandSpec CRON_RESUME = CliCommandSpec.builder("cron.resume")
            .post("/api/cron/jobs/{id}/resume")
            .description("Resume a cron job")
            .build();

    public static final CliCommandSpec CRON_RUN = CliCommandSpec.builder("cron.run")
            .post("/api/cron/jobs/{id}/run")
            .description("Run a cron job now")
            .build();

    public static final CliCommandSpec CRON_STATE = CliCommandSpec.builder("cron.state")
            .get("/api/cron/jobs/{id}/state")
            .description("Get cron runtime state")
            .build();

    public static final CliCommandSpec ENV_LIST = CliCommandSpec.builder("env.list")
            .get("/api/envs")
            .description("List environment variables")
            .build();

    public static final CliCommandSpec ENV_GET = CliCommandSpec.builder("env.get")
            .get("/api/envs/{key}")
            .description("Get an environment variable")
            .build();

    public static final CliCommandSpec ENV_SET = CliCommandSpec.builder("env.set")
            .put("/api/envs/{key}")
            .description("Set an environment variable")
            .build();

    public static final CliCommandSpec ENV_DELETE = CliCommandSpec.builder("env.delete")
            .delete("/api/envs/{key}")
            .description("Delete an environment variable")
            .build();

    public static final CliCommandSpec PLUGIN_LIST = CliCommandSpec.builder("plugin.list")
            .get("/api/plugins")
            .description("List plugins")
            .build();

    public static final CliCommandSpec PLUGIN_CATALOG = CliCommandSpec.builder("plugin.catalog")
            .get("/api/plugins/catalog")
            .description("List plugin catalog")
            .build();

    public static final CliCommandSpec PLUGIN_SEARCH = CliCommandSpec.builder("plugin.search")
            .get("/api/plugins/market/search")
            .description("Search plugin market")
            .build();

    public static final CliCommandSpec PLUGIN_INSTALL = CliCommandSpec.builder("plugin.install")
            .post("/api/plugins/install")
            .description("Install plugin")
            .build();

    public static final CliCommandSpec PLUGIN_INFO = CliCommandSpec.builder("plugin.info")
            .get("/api/plugins/{pluginId}")
            .description("Get plugin info")
            .build();

    public static final CliCommandSpec PLUGIN_STATUS = CliCommandSpec.builder("plugin.status")
            .get("/api/plugins/{pluginId}/status")
            .description("Get plugin status")
            .build();

    public static final CliCommandSpec PLUGIN_RELOAD = CliCommandSpec.builder("plugin.reload")
            .post("/api/plugins/reload")
            .description("Reload plugins")
            .build();

    public static final CliCommandSpec PLUGIN_UNINSTALL = CliCommandSpec.builder("plugin.uninstall")
            .delete("/api/plugins/{pluginId}")
            .description("Uninstall plugin")
            .build();

    public static final CliCommandSpec CONSOLE_TASK_SUBMIT = CliCommandSpec.builder("console.task-submit")
            .post("/api/console/chat/task")
            .description("Submit console task")
            .build();

    public static final CliCommandSpec CONSOLE_TASK_STATUS = CliCommandSpec.builder("console.task-status")
            .get("/api/console/chat/task/{taskId}")
            .description("Get console task status")
            .build();

    public static final CliCommandSpec BACKEND_LOGS = CliCommandSpec.builder("backend.logs")
            .get("/api/console/debug/backend-logs?lines={lines}")
            .description("Read backend logs")
            .build();

    public static final CliCommandSpec SHUTDOWN = CliCommandSpec.builder("shutdown")
            .post("/api/agent/shutdown")
            .description("Request backend shutdown")
            .build();

}
