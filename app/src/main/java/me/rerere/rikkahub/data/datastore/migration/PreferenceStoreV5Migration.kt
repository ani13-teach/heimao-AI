package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

private val LEGACY_TTS_PROVIDERS = stringPreferencesKey("tts_providers")
private val LEGACY_SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
private val LEGACY_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")
private val LEGACY_ASR_PROVIDERS = stringPreferencesKey("asr_providers")
private val LEGACY_SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

class PreferenceStoreV5Migration : androidx.datastore.core.DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 5
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs.remove(LEGACY_TTS_PROVIDERS)
        prefs.remove(LEGACY_SELECTED_TTS_PROVIDER)
        prefs.remove(LEGACY_TTS_PLAYBACK_SPEED)
        prefs.remove(LEGACY_ASR_PROVIDERS)
        prefs.remove(LEGACY_SELECTED_ASR_PROVIDER)
        prefs[SettingsStore.DISPLAY_SETTING]?.let { displayJson ->
            prefs[SettingsStore.DISPLAY_SETTING] = removeLegacyVoiceDisplayFields(displayJson)
        }
        prefs[SettingsStore.ASSISTANTS]?.let { assistantsJson ->
            prefs[SettingsStore.ASSISTANTS] = removeLegacyTtsLocalTools(assistantsJson)
        }
        prefs[SettingsStore.VERSION] = 5
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal fun removeLegacyVoiceDisplayFields(displayJson: String): String {
    val legacyFields = setOf(
        "ttsOnlyReadQuoted",
        "ttsOnlyReadOutsideBrackets",
        "autoPlayTTSAfterGeneration",
    )
    return runCatching {
        val display = JsonInstant.parseToJsonElement(displayJson) as? JsonObject
            ?: return@runCatching displayJson
        val cleaned = JsonObject(display.filterKeys { it !in legacyFields })
        if (cleaned == display) displayJson else JsonInstant.encodeToString(cleaned)
    }.getOrElse { displayJson }
}

internal fun removeLegacyTtsLocalTools(assistantsJson: String): String {
    return runCatching {
        val assistants = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
            ?: return@runCatching assistantsJson
        val migrated = JsonArray(assistants.map { assistant ->
            val assistantObject = assistant as? JsonObject ?: return@map assistant
            val localTools = assistantObject["localTools"] as? JsonArray
                ?: return@map assistant
            val filtered = JsonArray(localTools.filterNot { option ->
                (option as? JsonObject)?.get("type")?.jsonPrimitive?.content == "tts"
            })
            if (filtered == localTools) assistant
            else JsonObject(assistantObject.toMutableMap().apply { put("localTools", filtered) })
        })
        if (migrated == assistants) assistantsJson else JsonInstant.encodeToString(migrated)
    }.getOrElse { assistantsJson }
}
