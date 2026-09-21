package me.rerere.rikkahub.data.workspace

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceArchiveCredentialMergeTest {
    @Test
    fun `blank local credentials never erase archived ones`() {
        val archive = Settings(
            providers = listOf(ProviderSetting.OpenAI(id = PROVIDER_ID, apiKey = "archived-key")),
            webDavConfig = WebDavConfig(username = "archived-user", password = "archived-webdav"),
            s3Config = S3Config(accessKeyId = "archived-access", secretAccessKey = "archived-secret"),
        )
        val device = Settings(
            providers = listOf(ProviderSetting.OpenAI(id = PROVIDER_ID, apiKey = "")),
        )

        val merged = mergeLocalCredentials(current = device, restored = archive)

        assertEquals("archived-key", (merged.providers.single() as ProviderSetting.OpenAI).apiKey)
        assertEquals("archived-user", merged.webDavConfig.username)
        assertEquals("archived-webdav", merged.webDavConfig.password)
        assertEquals("archived-access", merged.s3Config.accessKeyId)
        assertEquals("archived-secret", merged.s3Config.secretAccessKey)
    }

    @Test
    fun `credentials typed on this device still win over the archive`() {
        val archive = Settings(
            providers = listOf(ProviderSetting.OpenAI(id = PROVIDER_ID, apiKey = "archived-key")),
            webDavConfig = WebDavConfig(password = "archived-webdav"),
        )
        val device = Settings(
            providers = listOf(ProviderSetting.OpenAI(id = PROVIDER_ID, apiKey = "device-key")),
            webDavConfig = WebDavConfig(password = "device-webdav"),
        )

        val merged = mergeLocalCredentials(current = device, restored = archive)

        assertEquals("device-key", (merged.providers.single() as ProviderSetting.OpenAI).apiKey)
        assertEquals("device-webdav", merged.webDavConfig.password)
    }

    @Test
    fun `archive providers without a local counterpart are copied unchanged`() {
        val archive = Settings(
            providers = listOf(ProviderSetting.OpenAI(id = PROVIDER_ID, apiKey = "archived-key")),
        )

        val merged = mergeLocalCredentials(current = Settings(), restored = archive)

        assertEquals(archive.providers, merged.providers)
    }

    private companion object {
        val PROVIDER_ID = Uuid.parse("a8f6d0c2-6a2c-4a9a-9a1b-2f36c9e4a1d0")
    }
}
