package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceStoreV4MigrationTest {
    @Test
    fun `version 2 migration removes legacy quick message data and preserves assistant fields`() = runBlocking {
        val migrated = migrate(version = 2)

        assertLegacyQuickMessageDataRemoved(migrated)
    }

    @Test
    fun `version 3 migration removes legacy quick message data and preserves assistant fields`() = runBlocking {
        val migrated = migrate(version = 3)

        assertLegacyQuickMessageDataRemoved(migrated)
    }

    @Test
    fun `unversioned migration removes legacy quick message data and preserves assistant fields`() = runBlocking {
        val migrated = migrate(version = null)

        assertLegacyQuickMessageDataRemoved(migrated)
    }

    @Test
    fun `backup migration removes root and assistant legacy quick message fields`() {
        val migrated = SettingsJsonMigrator.migrate(
            """
            {
              "quickMessages": [{"title": "legacy"}],
              "keepRoot": "value",
              "assistants": $ASSISTANTS_JSON
            }
            """.trimIndent()
        )

        val root = JsonInstant.parseToJsonElement(migrated).jsonObject
        assertFalse("quickMessages" in root)
        assertEquals("value", root["keepRoot"]?.toString()?.trim('"'))
        assertAssistantFields(root["assistants"]!!.jsonArray[0].jsonObject)
    }

    private suspend fun migrate(version: Int?): Preferences {
        val migration = PreferenceStoreV4Migration()
        val preferences = emptyPreferences().toMutablePreferences().apply {
            version?.let { this[SettingsStore.VERSION] = it }
            this[SettingsStore.ASSISTANTS] = ASSISTANTS_JSON
            this[LEGACY_QUICK_MESSAGES] = "[{\"title\":\"legacy\"}]"
        }

        assertTrue(migration.shouldMigrate(preferences))
        return migration.migrate(preferences)
    }

    private fun assertLegacyQuickMessageDataRemoved(preferences: Preferences) {
        assertEquals(4, preferences[SettingsStore.VERSION])
        assertNull(preferences[LEGACY_QUICK_MESSAGES])

        val assistants = JsonInstant.parseToJsonElement(
            preferences[SettingsStore.ASSISTANTS]!!
        ).jsonArray
        assertAssistantFields(assistants[0].jsonObject)
        assertSecondAssistantFields(assistants[1].jsonObject)
    }

    private fun assertAssistantFields(assistant: JsonObject) {
        assertFalse("quickMessages" in assistant)
        assertFalse("quickMessageIds" in assistant)
        assertEquals("[\"mode-1\"]", assistant["modeInjectionIds"].toString())
        assertEquals("[\"lorebook-1\"]", assistant["lorebookIds"].toString())
        assertEquals("[\"skill-a\"]", assistant["enabledSkills"].toString())
        assertEquals("{\"nested\":true}", assistant["custom"].toString())
    }

    private fun assertSecondAssistantFields(assistant: JsonObject) {
        assertFalse("quickMessages" in assistant)
        assertFalse("quickMessageIds" in assistant)
        assertEquals("unchanged", assistant["name"]?.toString()?.trim('"'))
    }

    private companion object {
        val LEGACY_QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        const val ASSISTANTS_JSON = """
            [
              {
                "id": "assistant-1",
                "quickMessages": [{"title": "legacy"}],
                "quickMessageIds": ["quick-message-1"],
                "modeInjectionIds": ["mode-1"],
                "lorebookIds": ["lorebook-1"],
                "enabledSkills": ["skill-a"],
                "custom": {"nested": true}
              },
              {
                "id": "assistant-2",
                "name": "unchanged",
                "quickMessages": [{"title": "legacy 2"}],
                "quickMessageIds": ["quick-message-2"]
              }
            ]
        """
    }
}
