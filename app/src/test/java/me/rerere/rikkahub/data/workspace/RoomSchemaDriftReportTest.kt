package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSchemaDriftReportTest {
    @Test
    fun `report carries the schema structure needed to name a drifting database`() {
        val snapshot = roomSchema30ExpectedSnapshotForTest()
        val report = roomSchemaDriftReport(
            snapshot = snapshot,
            difference = "ConversationEntity unexpected columns=[legacy_column]",
            appVersion = "2.4.9",
            appVersionCode = "176",
        )

        assertTrue(report, report.contains("app=2.4.9 (176)"))
        assertTrue(report, report.contains("user_version=30 expected=30"))
        assertTrue(report, report.contains("difference=ConversationEntity unexpected columns=[legacy_column]"))
        assertTrue(report, report.contains("table ConversationEntity"))
        assertTrue(report, report.contains("workspace_snapshots"))
        assertTrue(report, report.contains("index_ConversationEntity_conversation_number"))
        assertTrue(report, report.contains("message_fts using fts5("))
    }

    @Test
    fun `report is truncated instead of unbounded`() {
        val snapshot = roomSchema30ExpectedSnapshotForTest()
        val report = roomSchemaDriftReport(
            snapshot = snapshot,
            difference = "many",
            appVersion = "2.4.9",
            appVersionCode = "176",
            maxChars = 256,
        )

        assertTrue(report, report.length <= 256 + "\n<truncated>\n".length)
        assertTrue(report, report.endsWith("<truncated>\n"))
    }
}
