package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

private val LEGACY_QUICK_MESSAGES = stringPreferencesKey("quick_messages")

class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        prefs.remove(LEGACY_QUICK_MESSAGES)
        prefs[SettingsStore.ASSISTANTS]?.let { assistantsJson ->
            prefs[SettingsStore.ASSISTANTS] = removeLegacyQuickMessageFields(assistantsJson)
        }
        prefs[SettingsStore.VERSION] = 4

        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal fun removeLegacyQuickMessageFields(assistantsJson: String): String {
    return runCatching {
        val assistants = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
            ?: return@runCatching assistantsJson
        val migrated = JsonArray(
            assistants.map { assistant ->
                val assistantObject = assistant as? JsonObject ?: return@map assistant
                if ("quickMessages" !in assistantObject && "quickMessageIds" !in assistantObject) {
                    assistant
                } else {
                    JsonObject(assistantObject.toMutableMap().apply {
                        remove("quickMessages")
                        remove("quickMessageIds")
                    })
                }
            }
        )
        if (migrated == assistants) assistantsJson else JsonInstant.encodeToString(migrated)
    }.getOrElse { assistantsJson }
}
