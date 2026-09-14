package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.RetryPolicy
import me.rerere.rikkahub.data.ai.AUTO_RETRY_MAX_ATTEMPTS
import me.rerere.rikkahub.data.ai.AUTO_RETRY_MAX_DURATION_MINUTES
import me.rerere.rikkahub.data.ai.AUTO_RETRY_MAX_INTERVAL_SECONDS
import me.rerere.rikkahub.data.ai.AUTO_RETRY_MIN_ATTEMPTS
import me.rerere.rikkahub.data.ai.AUTO_RETRY_MIN_DURATION_MINUTES
import me.rerere.rikkahub.data.ai.AUTO_RETRY_MIN_INTERVAL_SECONDS
import me.rerere.rikkahub.data.ai.toRetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoRetrySettingsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `defaults are used when an older backup has no auto retry fields`() {
        val settings = json.decodeFromString<Settings>("{}")

        assertEquals(true, settings.autoRetryEnabled)
        assertEquals(Settings.DEFAULT_AUTO_RETRY_INTERVAL_SECONDS, settings.autoRetryIntervalSeconds)
        assertEquals(
            Settings.DEFAULT_AUTO_RETRY_MAX_DURATION_MINUTES,
            settings.autoRetryMaxDurationMinutes,
        )
        assertEquals(Settings.DEFAULT_AUTO_RETRY_MAX_ATTEMPTS, settings.autoRetryMaxAttempts)
    }

    @Test
    fun `the default attempt limit matches the request layer default`() {
        assertEquals(RetryPolicy.DEFAULT_MAX_ATTEMPTS, Settings.DEFAULT_AUTO_RETRY_MAX_ATTEMPTS)
    }

    @Test
    fun `auto retry fields survive a serialization round trip`() {
        val settings = Settings(
            autoRetryEnabled = false,
            autoRetryIntervalSeconds = 12,
            autoRetryMaxDurationMinutes = 7,
            autoRetryMaxAttempts = 15,
        )

        val restored = json.decodeFromString<Settings>(json.encodeToString(settings))

        assertEquals(false, restored.autoRetryEnabled)
        assertEquals(12, restored.autoRetryIntervalSeconds)
        assertEquals(7, restored.autoRetryMaxDurationMinutes)
        assertEquals(15, restored.autoRetryMaxAttempts)
    }

    @Test
    fun `switching auto retry off maps to no policy`() {
        assertNull(Settings(autoRetryEnabled = false).toRetryPolicy())
    }

    @Test
    fun `enabled settings map to the configured interval and budget`() {
        val policy = Settings(
            autoRetryEnabled = true,
            autoRetryIntervalSeconds = 5,
            autoRetryMaxDurationMinutes = 3,
            autoRetryMaxAttempts = 17,
        ).toRetryPolicy()

        assertEquals(5_000L, policy?.intervalMillis)
        assertEquals(180_000L, policy?.maxDurationMillis)
        assertEquals(17, policy?.maxAttempts)
    }

    @Test
    fun `values outside the slider range are narrowed to the boundary`() {
        val overMax = Settings(
            autoRetryIntervalSeconds = 600,
            autoRetryMaxDurationMinutes = 999,
            autoRetryMaxAttempts = 99,
        ).toRetryPolicy()
        val underMin = Settings(
            autoRetryIntervalSeconds = 0,
            autoRetryMaxDurationMinutes = -5,
            autoRetryMaxAttempts = 0,
        ).toRetryPolicy()

        assertEquals(AUTO_RETRY_MAX_INTERVAL_SECONDS * 1_000L, overMax?.intervalMillis)
        assertEquals(AUTO_RETRY_MAX_DURATION_MINUTES * 60_000L, overMax?.maxDurationMillis)
        assertEquals(AUTO_RETRY_MAX_ATTEMPTS, overMax?.maxAttempts)
        assertEquals(AUTO_RETRY_MIN_INTERVAL_SECONDS * 1_000L, underMin?.intervalMillis)
        assertEquals(AUTO_RETRY_MIN_DURATION_MINUTES * 60_000L, underMin?.maxDurationMillis)
        assertEquals(AUTO_RETRY_MIN_ATTEMPTS, underMin?.maxAttempts)
    }
}
