package me.rerere.rikkahub.data.workspace

import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.ai.provider.ProviderSetting

/**
 * Keeps the credentials this device already holds when an archive is applied on top of it.
 *
 * A value the device actually has wins, because the user may have typed a newer key here after the
 * archive was written. A blank local value never wins: the archive is the only remaining copy of
 * those credentials after a reinstall, so an empty local field must not erase it.
 */
internal fun mergeLocalCredentials(current: Settings, restored: Settings): Settings {
    val currentProviders = current.providers.associateBy { it.id }
    val providers = restored.providers.map { provider ->
        val old = currentProviders[provider.id]
        when {
            old is ProviderSetting.OpenAI && provider is ProviderSetting.OpenAI ->
                provider.copy(apiKey = old.apiKey.ifBlank { provider.apiKey })

            old is ProviderSetting.Google && provider is ProviderSetting.Google ->
                provider.copy(
                    apiKey = old.apiKey.ifBlank { provider.apiKey },
                    privateKey = old.privateKey.ifBlank { provider.privateKey },
                    serviceAccountEmail = old.serviceAccountEmail.ifBlank { provider.serviceAccountEmail },
                    projectId = old.projectId.ifBlank { provider.projectId },
                )

            old is ProviderSetting.Claude && provider is ProviderSetting.Claude ->
                provider.copy(apiKey = old.apiKey.ifBlank { provider.apiKey })

            else -> provider
        }
    }
    val oldAssistants = current.assistants.associateBy { it.id }
    val assistants = restored.assistants.map { assistant ->
        val old = oldAssistants[assistant.id]
        if (old == null) assistant else assistant.copy(
            customHeaders = old.customHeaders.ifEmpty { assistant.customHeaders },
            customBodies = old.customBodies.ifEmpty { assistant.customBodies },
        )
    }
    val oldMcp = current.mcpServers.associateBy { it.id }
    val mcpServers = restored.mcpServers.map { server ->
        val old = oldMcp[server.id]
        if (old == null) server else when (server) {
            is McpServerConfig.SseTransportServer -> server.copy(
                commonOptions = server.commonOptions.copy(
                    headers = old.commonOptions.headers.ifEmpty { server.commonOptions.headers },
                    oauth = old.commonOptions.oauth ?: server.commonOptions.oauth,
                )
            )

            is McpServerConfig.StreamableHTTPServer -> server.copy(
                commonOptions = server.commonOptions.copy(
                    headers = old.commonOptions.headers.ifEmpty { server.commonOptions.headers },
                    oauth = old.commonOptions.oauth ?: server.commonOptions.oauth,
                )
            )
        }
    }
    return restored.copy(
        providers = providers,
        assistants = assistants,
        mcpServers = mcpServers,
        webDavConfig = restored.webDavConfig.copy(
            username = current.webDavConfig.username.ifBlank { restored.webDavConfig.username },
            password = current.webDavConfig.password.ifBlank { restored.webDavConfig.password },
        ),
        s3Config = restored.s3Config.copy(
            accessKeyId = current.s3Config.accessKeyId.ifBlank { restored.s3Config.accessKeyId },
            secretAccessKey = current.s3Config.secretAccessKey.ifBlank { restored.s3Config.secretAccessKey },
        ),
    )
}
