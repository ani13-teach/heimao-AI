package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ModelTransportMigrationTest {
    @Test
    fun `legacy assistant non-streaming choice migrates to its selected model`() {
        val model = Model(id = id("11111111-1111-1111-1111-111111111111"))
        val settings = Settings(
            chatModelId = model.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            assistants = listOf(Assistant(streamOutput = false)),
        )

        val migrated = settings.resolveLegacyModelTransports()

        assertFalse(migrated.providers.single().models.single().streamOutput!!)
    }

    @Test
    fun `conflicting legacy assistants choose conservative non-streaming per model`() {
        val model = Model(id = id("11111111-1111-1111-1111-111111111111"))
        val settings = Settings(
            chatModelId = model.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            assistants = listOf(
                Assistant(streamOutput = true),
                Assistant(streamOutput = false),
            ),
        )

        val migrated = settings.resolveLegacyModelTransports()

        assertFalse(migrated.providers.single().models.single().streamOutput!!)
    }

    @Test
    fun `settings with explicit model transports return unchanged`() {
        val model = Model(
            id = id("11111111-1111-1111-1111-111111111111"),
            streamOutput = true,
        )
        val settings = Settings(
            chatModelId = model.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
        )

        assertSame(settings, settings.resolveLegacyModelTransports())
    }

    @Test
    fun `unreferenced legacy model defaults to streaming`() {
        val selectedModel = Model(id = id("11111111-1111-1111-1111-111111111111"))
        val unreferencedModel = Model(id = id("22222222-2222-2222-2222-222222222222"))
        val settings = Settings(
            chatModelId = selectedModel.id,
            providers = listOf(
                ProviderSetting.OpenAI(models = listOf(selectedModel, unreferencedModel))
            ),
            assistants = listOf(Assistant(streamOutput = false)),
        )

        val migrated = settings.resolveLegacyModelTransports()

        assertTrue(migrated.providers.single().models.last().streamOutput!!)
    }

    @Test
    fun `explicit model choice is never overwritten by legacy assistant setting`() {
        val model = Model(
            id = id("11111111-1111-1111-1111-111111111111"),
            streamOutput = false,
        )
        val settings = Settings(
            chatModelId = model.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            assistants = listOf(Assistant(streamOutput = true)),
        )

        val migrated = settings.resolveLegacyModelTransports()

        assertFalse(migrated.providers.single().models.single().streamOutput!!)
    }

    private fun id(value: String): Uuid = Uuid.parse(value)
}
