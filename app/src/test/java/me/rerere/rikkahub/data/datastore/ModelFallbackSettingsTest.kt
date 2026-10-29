package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ModelFallbackSettingsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `older backups default all new chains to empty without losing existing chains`() {
        val title = Uuid.random()
        val translate = Uuid.random()
        val restored = json.decodeFromString<Settings>(
            """{"titleFallbackModelIds":["$title"],"translateFallbackModelIds":["$translate"]}"""
        )
        assertEquals(emptyList<Uuid>(), restored.fastFallbackModelIds)
        assertEquals(emptyList<Uuid>(), restored.suggestionFallbackModelIds)
        assertEquals(emptyList<Uuid>(), restored.ocrFallbackModelIds)
        assertEquals(emptyList<Uuid>(), restored.compressFallbackModelIds)
        assertEquals(listOf(title), restored.titleFallbackModelIds)
        assertEquals(listOf(translate), restored.translateFallbackModelIds)
    }

    private fun settings() = Settings(
        fastFallbackModelIds = List(11) { Uuid.random() },
        titleFallbackModelIds = List(2) { Uuid.random() },
        suggestionFallbackModelIds = List(3) { Uuid.random() },
        translateFallbackModelIds = List(4) { Uuid.random() },
        ocrFallbackModelIds = List(5) { Uuid.random() },
        compressFallbackModelIds = List(6) { Uuid.random() },
    )

    @Test
    fun `all chains survive JSON round trip including legacy lists exceeding runtime limit`() {
        val original = settings()
        val restored = json.decodeFromString<Settings>(json.encodeToString(original))
        assertEquals(original.fastFallbackModelIds, restored.fastFallbackModelIds)
        assertEquals(original.titleFallbackModelIds, restored.titleFallbackModelIds)
        assertEquals(original.suggestionFallbackModelIds, restored.suggestionFallbackModelIds)
        assertEquals(original.translateFallbackModelIds, restored.translateFallbackModelIds)
        assertEquals(original.ocrFallbackModelIds, restored.ocrFallbackModelIds)
        assertEquals(original.compressFallbackModelIds, restored.compressFallbackModelIds)
    }

    @Test
    fun `preferences write and read preserves order and does not truncate old configuration`() {
        val original = settings()
        val preferences = mutablePreferencesOf()
        preferences.writeSettings(original)
        listOf(
            SettingsStore.FAST_FALLBACK_MODELS to original.fastFallbackModelIds,
            SettingsStore.TITLE_FALLBACK_MODELS to original.titleFallbackModelIds,
            SettingsStore.SUGGESTION_FALLBACK_MODELS to original.suggestionFallbackModelIds,
            SettingsStore.TRANSLATE_FALLBACK_MODELS to original.translateFallbackModelIds,
            SettingsStore.OCR_FALLBACK_MODELS to original.ocrFallbackModelIds,
            SettingsStore.COMPRESS_FALLBACK_MODELS to original.compressFallbackModelIds,
        ).forEach { (key, expected) ->
            assertEquals(expected, preferences.readModelFallbackIds(key))
        }
        assertEquals("title_fallback_models", SettingsStore.TITLE_FALLBACK_MODELS.name)
        assertEquals("translate_fallback_models", SettingsStore.TRANSLATE_FALLBACK_MODELS.name)
    }

    @Test
    fun `missing preference keys read as empty chains`() {
        val preferences = mutablePreferencesOf()
        listOf(
            SettingsStore.FAST_FALLBACK_MODELS, SettingsStore.SUGGESTION_FALLBACK_MODELS,
            SettingsStore.OCR_FALLBACK_MODELS, SettingsStore.COMPRESS_FALLBACK_MODELS,
        ).forEach { key ->
            assertEquals(emptyList<Uuid>(), preferences.readModelFallbackIds(key))
        }
    }
}
