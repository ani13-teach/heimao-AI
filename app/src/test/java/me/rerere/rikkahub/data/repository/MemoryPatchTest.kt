package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPatchTest {

    private val content = """
        # 主轴参数
        主轴转速: 12000 rpm
        进给速度: 3000 mm/min
        冷却方式: 水冷
    """.trimIndent()

    @Test
    fun `single edit changes only the matched spot`() {
        val updated = applyMemoryEdits(
            content,
            listOf(MemoryEdit("主轴转速: 12000 rpm", "主轴转速: 12500 rpm")),
        )

        assertEquals(content.replace("12000 rpm", "12500 rpm"), updated)
        // 除命中处外逐字节相同
        assertEquals(content.length - 1, updated.length - 1)
        assertTrue(updated.contains("进给速度: 3000 mm/min"))
        assertTrue(updated.contains("冷却方式: 水冷"))
    }

    @Test
    fun `missing anchor throws with an actionable hint`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            applyMemoryEdits(content, listOf(MemoryEdit("主轴转速: 99999", "x")))
        }
        assertTrue(e.message!!.contains("not found"))
        assertTrue(e.message!!.contains("memory_read"))
    }

    @Test
    fun `ambiguous anchor throws instead of guessing`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            applyMemoryEdits("转速: 12000\n转速: 12000\n", listOf(MemoryEdit("转速: 12000", "转速: 12500")))
        }
        assertTrue(e.message!!.contains("2 places"))
    }

    @Test
    fun `edits are applied in order and both land`() {
        val updated = applyMemoryEdits(
            content,
            listOf(
                MemoryEdit("主轴转速: 12000 rpm", "主轴转速: 12500 rpm"),
                MemoryEdit("冷却方式: 水冷", "冷却方式: 风冷"),
            ),
        )

        assertTrue(updated.contains("主轴转速: 12500 rpm"))
        assertTrue(updated.contains("冷却方式: 风冷"))
        assertTrue(updated.contains("进给速度: 3000 mm/min"))
    }

    @Test
    fun `later edit works on the result of the earlier one`() {
        val updated = applyMemoryEdits(
            content,
            listOf(
                MemoryEdit("主轴转速: 12000 rpm", "主轴转速: 12500 rpm"),
                MemoryEdit("主轴转速: 12500 rpm", "主轴转速: 12500 rpm (2026-09 实测)"),
            ),
        )

        assertTrue(updated.contains("主轴转速: 12500 rpm (2026-09 实测)"))
    }

    @Test
    fun `later edit fails when an earlier edit removed its anchor`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            applyMemoryEdits(
                content,
                listOf(
                    MemoryEdit("冷却方式: 水冷", ""),
                    MemoryEdit("冷却方式: 水冷", "冷却方式: 风冷"),
                ),
            )
        }
        assertTrue(e.message!!.contains("edits[1]"))
        assertTrue(e.message!!.contains("not found"))
    }

    @Test
    fun `empty new text deletes the matched spot`() {
        val updated = applyMemoryEdits(content, listOf(MemoryEdit("冷却方式: 水冷", "")))

        assertEquals(content.removeSuffix("冷却方式: 水冷"), updated)
        assertTrue(!updated.contains("冷却方式"))
    }

    @Test
    fun `line trimmed matching tolerates indent and trailing whitespace`() {
        val updated = applyMemoryEdits(
            content,
            listOf(MemoryEdit("  主轴转速: 12000 rpm  ", "主轴转速: 12500 rpm")),
        )

        assertTrue(updated.contains("主轴转速: 12500 rpm"))
        assertTrue(updated.contains("进给速度: 3000 mm/min"))
    }

    @Test
    fun `line trimmed matching tolerates crlf`() {
        val crlf = "主轴转速: 12000\r\n进给速度: 3000\r\n"
        val updated = applyMemoryEdits(crlf, listOf(MemoryEdit("主轴转速: 12000", "主轴转速: 12500")))

        assertTrue(updated.contains("主轴转速: 12500"))
        assertTrue(updated.contains("进给速度: 3000"))
    }

    @Test
    fun `block with matching first and last line is not fuzzy matched`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            applyMemoryEdits(
                content,
                listOf(MemoryEdit("主轴转速: 12000 rpm\n进给速度: 9999 mm/min\n冷却方式: 水冷", "已替换")),
            )
        }
        assertTrue(e.message!!.contains("not found"))
    }

    @Test
    fun `empty old text is rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            applyMemoryEdits(content, listOf(MemoryEdit("", "x")))
        }
        assertTrue(e.message!!.contains("old_text"))
    }

    @Test
    fun `empty edit list is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { applyMemoryEdits(content, emptyList()) }
    }
}
