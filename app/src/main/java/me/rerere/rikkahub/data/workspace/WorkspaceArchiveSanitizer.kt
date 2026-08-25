package me.rerere.rikkahub.data.workspace

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.redactWorkspaceUrl

/**
 * Settings are portable metadata, not a secret backup. In particular, this function deliberately
 * has no field for the transport-specific [BackupPasswordStore] password.
 */
internal fun sanitizeWorkspaceArchiveSettings(settings: Settings): Settings = settings.copy(
    providers = settings.providers.map(::sanitizeProvider),
    assistants = settings.assistants.map { assistant ->
        assistant.copy(
            customHeaders = emptyList(),
            customBodies = emptyList(),
        )
    },
    mcpServers = settings.mcpServers.map(::sanitizeMcpServer),
    webDavConfig = settings.webDavConfig.copy(
        url = redactWorkspaceUrl(settings.webDavConfig.url),
        username = "",
        password = "",
    ),
    s3Config = settings.s3Config.copy(
        endpoint = redactWorkspaceUrl(settings.s3Config.endpoint),
        accessKeyId = "",
        secretAccessKey = "",
    ),
)

private fun sanitizeProvider(provider: ProviderSetting): ProviderSetting = when (provider) {
    is ProviderSetting.OpenAI -> provider.copy(
        apiKey = "",
        baseUrl = redactWorkspaceUrl(provider.baseUrl),
        models = provider.models.map(::sanitizeModel),
    )

    is ProviderSetting.Google -> provider.copy(
        apiKey = "",
        baseUrl = redactWorkspaceUrl(provider.baseUrl),
        privateKey = "",
        serviceAccountEmail = "",
        projectId = "",
        models = provider.models.map(::sanitizeModel),
    )

    is ProviderSetting.Claude -> provider.copy(
        apiKey = "",
        baseUrl = redactWorkspaceUrl(provider.baseUrl),
        models = provider.models.map(::sanitizeModel),
    )
}

private fun sanitizeModel(model: Model): Model = model.copy(
    customHeaders = emptyList(),
    customBodies = emptyList(),
    providerOverwrite = model.providerOverwrite?.let(::sanitizeProvider),
)

private fun sanitizeMcpServer(server: McpServerConfig): McpServerConfig {
    val common = server.commonOptions.copy(
        headers = emptyList(),
        oauth = server.commonOptions.oauth?.withoutSecrets(),
    )
    return when (server) {
        is McpServerConfig.SseTransportServer -> server.copy(
            commonOptions = common,
            url = redactWorkspaceUrl(server.url),
        )

        is McpServerConfig.StreamableHTTPServer -> server.copy(
            commonOptions = common,
            url = redactWorkspaceUrl(server.url),
        )
    }
}

private fun McpOAuthState.withoutSecrets(): McpOAuthState = copy(
    clientSecret = null,
    accessToken = null,
    refreshToken = null,
)
