package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceStoreV5MigrationTest {
    private val LEGACY_TTS_PROVIDERS = stringPreferencesKey("tts_providers")
    private val LEGACY_SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
    private val LEGACY_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")
    private val LEGACY_ASR_PROVIDERS = stringPreferencesKey("asr_providers")
    private val LEGACY_SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

    @Test
    fun `version 4 migration removes legacy voice keys and cleans display and assistant data`() = runBlocking {
        val migration = PreferenceStoreV5Migration()
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[SettingsStore.VERSION] = 4
            this[LEGACY_TTS_PROVIDERS] = "[{\"id\":\"tts-1\"}]"
            this[LEGACY_SELECTED_TTS_PROVIDER] = "tts-1"
            this[LEGACY_TTS_PLAYBACK_SPEED] = 1.25f
            this[LEGACY_ASR_PROVIDERS] = "[{\"id\":\"asr-1\"}]"
            this[LEGACY_SELECTED_ASR_PROVIDER] = "asr-1"
            this[SettingsStore.DISPLAY_SETTING] = DISPLAY_JSON
            this[SettingsStore.ASSISTANTS] = ASSISTANTS_JSON
        }

        assertTrue(migration.shouldMigrate(prefs))
        val migrated = migration.migrate(prefs)

        assertEquals(5, migrated[SettingsStore.VERSION])
        assertNull(migrated[LEGACY_TTS_PROVIDERS])
        assertNull(migrated[LEGACY_SELECTED_TTS_PROVIDER])
        assertNull(migrated[LEGACY_TTS_PLAYBACK_SPEED])
        assertNull(migrated[LEGACY_ASR_PROVIDERS])
        assertNull(migrated[LEGACY_SELECTED_ASR_PROVIDER])

        val display = JsonInstant.parseToJsonElement(migrated[SettingsStore.DISPLAY_SETTING]!!).jsonObject
        assertFalse("ttsOnlyReadQuoted" in display)
        assertFalse("ttsOnlyReadOutsideBrackets" in display)
        assertFalse("autoPlayTTSAfterGeneration" in display)
        assertEquals(true, display["dynamicColor"]?.toString()?.toBoolean())

        val assistants = JsonInstant.parseToJsonElement(migrated[SettingsStore.ASSISTANTS]!!).jsonArray
        val localTools = assistants[0].jsonObject["localTools"]!!.jsonArray
        val types = localTools.map { it.jsonObject["type"]?.toString()?.trim('"') }
        assertFalse(types.contains("tts"))
        assertTrue(types.contains("time_info"))
        assertTrue(types.contains("workspace_exec"))
    }

    @Test
    fun `version 5 and future should not migrate`() = runBlocking {
        val migration = PreferenceStoreV5Migration()
        for (v in listOf(5, 6)) {
            val prefs = emptyPreferences().toMutablePreferences().apply {
                this[SettingsStore.VERSION] = v
            }
            assertFalse(migration.shouldMigrate(prefs))
        }
    }

    @Test
    fun `unversioned migration removes legacy voice keys`() = runBlocking {
        val migration = PreferenceStoreV5Migration()
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[LEGACY_TTS_PROVIDERS] = "[{}]"
            this[SettingsStore.ASSISTANTS] = ASSISTANTS_JSON
        }
        assertTrue(migration.shouldMigrate(prefs))
        val migrated = migration.migrate(prefs)
        assertEquals(5, migrated[SettingsStore.VERSION])
        assertNull(migrated[LEGACY_TTS_PROVIDERS])
    }

    @Test
    fun `backup migration removes legacy voice fields from settings json`() {
        val migrated = SettingsJsonMigrator.migrate(
            """
            {
              "ttsProviders": [{"id": "tts-1"}],
              "selectedTTSProviderId": "tts-1",
              "defaultTTSPlaybackSpeed": 1.25,
              "asrProviders": [{"id": "asr-1"}],
              "selectedASRProviderId": "asr-1",
              "keepRoot": "value",
              "displaySetting": $DISPLAY_JSON,
              "assistants": $ASSISTANTS_JSON
            }
            """.trimIndent()
        )
        val root = JsonInstant.parseToJsonElement(migrated).jsonObject
        assertFalse("ttsProviders" in root)
        assertFalse("selectedTTSProviderId" in root)
        assertFalse("defaultTTSPlaybackSpeed" in root)
        assertFalse("asrProviders" in root)
        assertFalse("selectedASRProviderId" in root)
        assertEquals("value", root["keepRoot"]?.toString()?.trim('"'))

        val display = root["displaySetting"]?.let {
            JsonInstant.parseToJsonElement(JsonInstant.encodeToString(it)).jsonObject
        }
        assertFalse("ttsOnlyReadQuoted" in (display ?: kotlinx.serialization.json.buildJsonObject {}))

        val assistants = root["assistants"]!!.jsonArray
        val localTools = assistants[0].jsonObject["localTools"]!!.jsonArray
        val types = localTools.map { it.jsonObject["type"]?.toString()?.trim('"') }
        assertFalse(types.contains("tts"))
        assertTrue(types.contains("time_info"))
    }

    private companion object {
        const val DISPLAY_JSON = """{"dynamicColor":true,"ttsOnlyReadQuoted":false,"ttsOnlyReadOutsideBrackets":true,"autoPlayTTSAfterGeneration":false}"""

        const val ASSISTANTS_JSON = """[
            {
              "id": "assistant-1",
              "localTools": [
                {"type": "time_info"},
                {"type": "workspace_exec"},
                {"type": "tts"}
              ]
            }
        ]"""
    }
}
