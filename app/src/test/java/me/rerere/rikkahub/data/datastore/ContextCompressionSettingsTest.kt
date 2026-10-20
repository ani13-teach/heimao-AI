package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextCompressionSettingsTest {
    @Test
    fun `default compression settings are valid`() {
        assertNull(validateContextCompressionSettings(100_000, 10_000))
    }

    @Test
    fun `zero threshold disables compression and permits larger targets`() {
        assertNull(validateContextCompressionSettings(0, 1))
        assertNull(validateContextCompressionSettings(0, MAX_CONTEXT_TOKENS))
    }

    @Test
    fun `valid enabled settings include lower and upper boundaries`() {
        assertNull(validateContextCompressionSettings(2, 1))
        assertNull(validateContextCompressionSettings(MAX_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS - 1))
    }

    @Test
    fun `threshold outside supported range is rejected`() {
        listOf(-1, Int.MIN_VALUE, MAX_CONTEXT_TOKENS + 1, Int.MAX_VALUE).forEach { threshold ->
            assertEquals(
                "自动压缩触发阈值必须为 0～9999999，0 表示关闭自动压缩",
                validateContextCompressionSettings(threshold, 1),
            )
        }
    }

    @Test
    fun `target outside supported range is rejected even when disabled`() {
        listOf(0, -1, Int.MIN_VALUE, MAX_CONTEXT_TOKENS + 1, Int.MAX_VALUE).forEach { target ->
            listOf(0, 100_000).forEach { threshold ->
                assertEquals(
                    "自动压缩目标必须为 1～9999999，不能为 0",
                    validateContextCompressionSettings(threshold, target),
                )
            }
        }
    }

    @Test
    fun `equal target and threshold are rejected when enabled`() {
        listOf(1, 10_000, MAX_CONTEXT_TOKENS).forEach { threshold ->
            assertEquals(
                "开启自动压缩时，压缩目标必须小于触发阈值",
                validateContextCompressionSettings(threshold, threshold),
            )
        }
    }

    @Test
    fun `larger targets are rejected when enabled`() {
        assertEquals(
            "开启自动压缩时，压缩目标必须小于触发阈值",
            validateContextCompressionSettings(100_000, 100_001),
        )
        assertEquals(
            "开启自动压缩时，压缩目标必须小于触发阈值",
            validateContextCompressionSettings(1, MAX_CONTEXT_TOKENS),
        )
    }
}
