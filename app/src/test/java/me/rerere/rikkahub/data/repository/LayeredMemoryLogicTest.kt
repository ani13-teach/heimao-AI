package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LayeredMemoryLogicTest {
    private fun memory(
        id: Int,
        category: String = "GENERAL",
        content: String = "",
        confidenceFlags: String = "[]",
        tags: String = "[]",
        product: String = "",
        status: String = "active",
    ) = MemoryEntity(
        id = id,
        assistantId = "assistant-a",
        content = content,
        category = category,
        title = "title $id",
        updated = "2025-01-01",
        confidenceFlags = confidenceFlags,
        tags = tags,
        product = product,
        status = status,
    )

    // ---- escapeLike ----

    @Test
    fun `escapeLike escapes percent underscore and backslash`() {
        assertEquals("100\\%", escapeLike("100%"))
        assertEquals("a\\_b", escapeLike("a_b"))
        assertEquals("a\\\\b", escapeLike("a\\b"))
        assertEquals("plain", escapeLike("plain"))
        assertEquals("", escapeLike(""))
    }

    // ---- isStale ----

    @Test
    fun `isStale returns false for unknown date`() {
        assertFalse(isStale("1970-01-01", LocalDate.of(2024, 1, 10), 7))
    }

    @Test
    fun `isStale returns false for unparseable date`() {
        assertFalse(isStale("not-a-date", LocalDate.of(2024, 1, 10), 7))
        assertFalse(isStale("", LocalDate.of(2024, 1, 10), 7))
    }

    @Test
    fun `isStale returns true when older than the threshold`() {
        // 2024-01-01 is 9 days before 2024-01-10 -> stale with days=7
        assertTrue(isStale("2024-01-01", LocalDate.of(2024, 1, 10), 7))
    }

    @Test
    fun `isStale boundary at exactly days is not stale`() {
        // exactly 7 days old -> not stale
        assertFalse(isStale("2024-01-03", LocalDate.of(2024, 1, 10), 7))
        // 8 days old -> stale
        assertTrue(isStale("2024-01-02", LocalDate.of(2024, 1, 10), 7))
    }

    @Test
    fun `isStale respects days parameter`() {
        // 2024-01-01 距 2024-01-10 只有 9 天：days=7 时过期，days=30/365 时不过期
        assertTrue(isStale("2024-01-01", LocalDate.of(2024, 1, 10), 7))
        assertFalse(isStale("2024-01-01", LocalDate.of(2024, 1, 10), 30))
        assertFalse(isStale("2024-01-01", LocalDate.of(2024, 1, 10), 365))
    }

    // ---- detectConflicts ----

    @Test
    fun `detectConflicts reports same key different value`() {
        val entries = listOf(
            memory(1, category = "PARAMS", product = "P1", tags = """["params"]""", content = "voltage=5V, current: 2A"),
            memory(2, category = "PARAMS", product = "P1", tags = """["params"]""", content = "voltage=3.3V, current: 2A"),
        )
        val conflicts = detectConflicts(entries)
        assertEquals(1, conflicts.size)
        val report = conflicts.single()
        assertEquals("voltage", report.key)
        assertEquals("5V", report.valueA)
        assertEquals("3.3V", report.valueB)
        assertEquals(1, report.idA)
        assertEquals(2, report.idB)
    }

    @Test
    fun `detectConflicts ignores same key same value`() {
        val entries = listOf(
            memory(1, category = "PARAMS", product = "P1", tags = """["params"]""", content = "voltage=5V"),
            memory(2, category = "PARAMS", product = "P1", tags = """["params"]""", content = "voltage=5V"),
        )
        assertTrue(detectConflicts(entries).isEmpty())
    }

    @Test
    fun `detectConflicts requires same category`() {
        val entries = listOf(
            memory(1, category = "PARAMS", product = "P1", tags = """["params"]""", content = "voltage=5V"),
            memory(2, category = "GENERAL", product = "P1", tags = """["params"]""", content = "voltage=3.3V"),
        )
        assertTrue(detectConflicts(entries).isEmpty())
    }

    @Test
    fun `detectConflicts requires matching product when both set`() {
        val entries = listOf(
            memory(1, category = "PARAMS", product = "P1", tags = """["params"]""", content = "voltage=5V"),
            memory(2, category = "PARAMS", product = "P2", tags = """["params"]""", content = "voltage=3.3V"),
        )
        assertTrue(detectConflicts(entries).isEmpty())
    }

    @Test
    fun `detectConflicts ignores product condition when both empty`() {
        val entries = listOf(
            memory(1, category = "PARAMS", tags = """["params"]""", content = "voltage=5V"),
            memory(2, category = "PARAMS", tags = """["params"]""", content = "voltage=3.3V"),
        )
        assertEquals(1, detectConflicts(entries).size)
    }

    @Test
    fun `detectConflicts requires overlapping tags`() {
        val entries = listOf(
            memory(1, category = "PARAMS", product = "P1", tags = """["a"]""", content = "voltage=5V"),
            memory(2, category = "PARAMS", product = "P1", tags = """["b"]""", content = "voltage=3.3V"),
        )
        assertTrue(detectConflicts(entries).isEmpty())
    }

    @Test
    fun `detectConflicts extracts multiple key value pairs`() {
        val entries = listOf(
            memory(1, category = "PARAMS", product = "P1", tags = """["params"]""", content = "voltage=5V, current=2A"),
            memory(2, category = "PARAMS", product = "P1", tags = """["params"]""", content = "voltage=5V, current=3A"),
        )
        val conflicts = detectConflicts(entries)
        assertEquals(1, conflicts.size)
        assertEquals("current", conflicts.single().key)
    }

    // ---- suggestConfidenceUpgrades ----

    @Test
    fun `suggestUpgrades flags verified record when warning flag confirmed by another record`() {
        val entries = listOf(
            memory(
                id = 1,
                category = "PARAMS",
                product = "P1",
                tags = """["params"]""",
                content = "baud=115200",
                confidenceFlags = """["⚠️单板待确认"]""",
            ),
            memory(
                id = 2,
                category = "PARAMS",
                product = "P1",
                tags = """["params"]""",
                content = "baud=115200",
                confidenceFlags = """["✅实测已验证"]""",
            ),
        )
        val upgrades = suggestConfidenceUpgrades(entries)
        assertEquals(1, upgrades.size)
        val suggestion = upgrades.single()
        assertEquals(1, suggestion.id)
        assertEquals("baud", suggestion.key)
        assertEquals("115200", suggestion.value)
        assertTrue(suggestion.reason.contains("✅实测已验证"))
    }

    @Test
    fun `suggestUpgrades empty when no warning flag present`() {
        val entries = listOf(
            memory(1, category = "PARAMS", product = "P1", tags = """["params"]""", content = "baud=115200", confidenceFlags = """["✅实测已验证"]"""),
            memory(2, category = "PARAMS", product = "P1", tags = """["params"]""", content = "baud=115200", confidenceFlags = """["✅实测已验证"]"""),
        )
        assertTrue(suggestConfidenceUpgrades(entries).isEmpty())
    }

    @Test
    fun `suggestUpgrades empty when values differ`() {
        val entries = listOf(
            memory(1, category = "PARAMS", product = "P1", tags = """["params"]""", content = "baud=115200", confidenceFlags = """["⚠️单板待确认"]"""),
            memory(2, category = "PARAMS", product = "P1", tags = """["params"]""", content = "baud=9600", confidenceFlags = """["✅实测已验证"]"""),
        )
        assertTrue(suggestConfidenceUpgrades(entries).isEmpty())
    }

    @Test
    fun `suggestUpgrades empty when both records unverified`() {
        // 两条都带 ⚠️ 的记录不能互相佐证，不产生升级建议
        val entries = listOf(
            memory(1, category = "PARAMS", product = "P1", tags = """["params"]""", content = "baud=115200", confidenceFlags = """["⚠️单板待确认"]"""),
            memory(2, category = "PARAMS", product = "P1", tags = """["params"]""", content = "baud=115200", confidenceFlags = """["⚠️单板待确认"]"""),
        )
        assertTrue(suggestConfidenceUpgrades(entries).isEmpty())
    }

    @Test
    fun `suggestUpgrades empty when corroborating record also carries warning flag`() {
        // 佐证记录即使含 ✅，只要还带 ⚠️/待确认，就不算已验证
        val entries = listOf(
            memory(1, category = "PARAMS", product = "P1", tags = """["params"]""", content = "baud=115200", confidenceFlags = """["⚠️单板待确认"]"""),
            memory(2, category = "PARAMS", product = "P1", tags = """["params"]""", content = "baud=115200", confidenceFlags = """["✅实测已验证","⚠️单板待确认"]"""),
        )
        assertTrue(suggestConfidenceUpgrades(entries).isEmpty())
    }

    @Test
    fun `suggestUpgrades dedupes repeated suggestions`() {
        val entries = listOf(
            memory(1, category = "PARAMS", product = "P1", tags = """["params"]""", content = "baud=115200, parity=none", confidenceFlags = """["⚠️单板待确认"]"""),
            memory(2, category = "PARAMS", product = "P1", tags = """["params"]""", content = "baud=115200, parity=none", confidenceFlags = """["✅实测已验证"]"""),
            memory(3, category = "PARAMS", product = "P1", tags = """["params"]""", content = "baud=115200, parity=none", confidenceFlags = """["✅实测已验证"]"""),
        )
        val upgrades = suggestConfidenceUpgrades(entries)
        // record #1 gets one suggestion per key, deduped across confirmers
        assertEquals(2, upgrades.size)
        assertTrue(upgrades.all { it.id == 1 })
        assertEquals(setOf("baud", "parity"), upgrades.map { it.key }.toSet())
    }
}
