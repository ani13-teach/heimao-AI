package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MemorySnapshotTest {
    @Test
    fun `created memory is emitted once then committed to snapshot`() {
        val delta = assertChangeIsEmittedOnce(
            initial = listOf(memory(1, "existing")),
            current = listOf(memory(2, "created"), memory(1, "existing")),
        )

        assertTrue(delta.contains("created"))
        assertTrue(delta.contains("\"id\": 2"))
    }

    @Test
    fun `edited memory is emitted once then committed to snapshot`() {
        val delta = assertChangeIsEmittedOnce(
            initial = listOf(memory(1, "before")),
            current = listOf(memory(1, "after")),
        )

        assertTrue(delta.contains("after"))
        assertFalse(delta.contains("before"))
    }

    @Test
    fun `deleted memory is emitted once then committed to snapshot`() {
        val delta = assertChangeIsEmittedOnce(
            initial = listOf(memory(2, "keep"), memory(1, "delete")),
            current = listOf(memory(2, "keep")),
        )

        assertTrue(Regex("\\\"deleted\\\"\\s*:\\s*\\[\\s*1\\s*]").containsMatchIn(delta))
    }

    @Test
    fun `uncommitted change is emitted again on the next model request`() {
        val snapshots = mutableMapOf<String, MemorySnapshotState>()
        val initial = listOf(memory(1, "before"))
        val current = listOf(memory(1, "after"))
        prepareMemorySnapshot(snapshots, "conversation", initial)

        val skipped = prepareMemorySnapshot(snapshots, "conversation", current)
        val retried = prepareMemorySnapshot(snapshots, "conversation", current)

        assertEquals(current, skipped.snapshot)
        assertEquals(1, skipped.delta.size)
        assertEquals(current, retried.snapshot)
        assertEquals(1, retried.delta.size)
    }

    @Test
    fun `new projection followed by stale projection cannot regress when new commits first`() {
        val snapshots = mutableMapOf<String, MemorySnapshotState>()
        val initial = listOf(memory(1, "initial"))
        val newer = listOf(memory(1, "newer"))
        val stale = listOf(memory(1, "stale"))
        prepareMemorySnapshot(snapshots, "conversation", initial)

        val newerResolution = prepareMemorySnapshot(snapshots, "conversation", newer)
        val staleResolution = prepareMemorySnapshot(snapshots, "conversation", stale)
        assertTrue(commitMemorySnapshot(snapshots, newerResolution.commit, newer))
        assertFalse(commitMemorySnapshot(snapshots, staleResolution.commit, newer))

        assertEquals("newer", snapshots.getValue("conversation").snapshot.single().content)
        assertTrue(prepareMemorySnapshot(snapshots, "conversation", newer).delta.isEmpty())
    }

    @Test
    fun `new projection followed by stale projection cannot regress when stale commits first`() {
        val snapshots = mutableMapOf<String, MemorySnapshotState>()
        val initial = listOf(memory(1, "initial"))
        val newer = listOf(memory(1, "newer"))
        val stale = listOf(memory(1, "stale"))
        prepareMemorySnapshot(snapshots, "conversation", initial)

        val newerResolution = prepareMemorySnapshot(snapshots, "conversation", newer)
        val staleResolution = prepareMemorySnapshot(snapshots, "conversation", stale)
        assertFalse(commitMemorySnapshot(snapshots, staleResolution.commit, newer))
        assertTrue(commitMemorySnapshot(snapshots, newerResolution.commit, newer))

        assertEquals("newer", snapshots.getValue("conversation").snapshot.single().content)
        assertTrue(prepareMemorySnapshot(snapshots, "conversation", newer).delta.isEmpty())
    }

    @Test
    fun `commit is rejected when candidate differs from latest Room projection`() {
        val snapshots = mutableMapOf<String, MemorySnapshotState>()
        val initial = listOf(memory(1, "initial"))
        val candidate = listOf(memory(1, "candidate"))
        val latestRoom = listOf(memory(1, "latest"))
        prepareMemorySnapshot(snapshots, "conversation", initial)

        val resolution = prepareMemorySnapshot(snapshots, "conversation", candidate)

        assertFalse(commitMemorySnapshot(snapshots, resolution.commit, latestRoom))
        assertEquals("initial", snapshots.getValue("conversation").snapshot.single().content)
    }

    @Test
    fun `metadata-only changes do not create a memory patch`() {
        val snapshots = mutableMapOf<String, MemorySnapshotState>()
        prepareMemorySnapshot(
            snapshots,
            "conversation",
            listOf(AssistantMemory(id = 1, content = "same", title = "before")),
        )

        val resolution = prepareMemorySnapshot(
            snapshots,
            "conversation",
            listOf(AssistantMemory(id = 1, content = "same", title = "after", tags = listOf("new"))),
        )

        assertTrue(resolution.delta.isEmpty())
        assertEquals(0, snapshots.getValue("conversation").patchCount)
    }

    @Test
    fun `patch blocks remain ordered and later upsert and tombstone override older content`() {
        val snapshots = mutableMapOf<String, MemorySnapshotState>()
        prepareMemorySnapshot(snapshots, "conversation", listOf(memory(1, "base")))

        listOf(
            listOf(memory(1, "edited")),
            emptyList(),
            listOf(memory(1, "recreated")),
        ).forEach { roomProjection ->
            val resolution = prepareMemorySnapshot(snapshots, "conversation", roomProjection)
            commitMemorySnapshot(snapshots, resolution.commit, roomProjection)
        }

        val blocks = snapshots.getValue("conversation").cacheBlocks.map { it.text }
        assertEquals(4, blocks.size)
        assertTrue(blocks[0].contains("Memory Base"))
        assertTrue(blocks[0].contains("base"))
        assertTrue(blocks[1].contains("edited"))
        assertTrue(Regex("\\\"deleted\\\"\\s*:\\s*\\[\\s*1\\s*]").containsMatchIn(blocks[2]))
        assertTrue(blocks[3].contains("recreated"))
        assertEquals("recreated", snapshots.getValue("conversation").snapshot.single().content)
    }

    @Test
    fun `threshold candidate preserves committed patch state until commit then compacts`() {
        val snapshots = mutableMapOf<String, MemorySnapshotState>()
        var current = listOf(memory(1, "v0"))
        prepareMemorySnapshot(snapshots, "conversation", current)

        repeat(MEMORY_PATCH_COMPACTION_COUNT - 1) { index ->
            current = listOf(memory(1, "v${index + 1}"))
            val resolution = prepareMemorySnapshot(snapshots, "conversation", current)
            commitMemorySnapshot(snapshots, resolution.commit, current)
        }
        val beforeCandidate = snapshots.getValue("conversation")
        assertEquals(MEMORY_PATCH_COMPACTION_COUNT, beforeCandidate.cacheBlocks.size)
        assertEquals(MEMORY_PATCH_COMPACTION_COUNT - 1, beforeCandidate.patchCount)

        current = listOf(memory(1, "v$MEMORY_PATCH_COMPACTION_COUNT"))
        val thresholdCandidate = prepareMemorySnapshot(snapshots, "conversation", current)
        val uncommitted = snapshots.getValue("conversation")

        assertEquals(beforeCandidate.cacheBlocks, uncommitted.cacheBlocks)
        assertEquals(beforeCandidate.patchCount, uncommitted.patchCount)
        assertEquals(beforeCandidate.patchBytes, uncommitted.patchBytes)
        assertEquals(beforeCandidate.revision, uncommitted.revision)
        assertEquals(beforeCandidate.cacheBlocks, thresholdCandidate.claudeCacheBlocks)
        assertEquals(1, thresholdCandidate.commit!!.nextState.cacheBlocks.size)
        assertEquals(0, thresholdCandidate.commit.nextState.patchCount)

        commitMemorySnapshot(snapshots, thresholdCandidate.commit, current)
        val compacted = snapshots.getValue("conversation")
        assertEquals(1, compacted.cacheBlocks.size)
        assertTrue(compacted.cacheBlocks.single().text.contains("v$MEMORY_PATCH_COMPACTION_COUNT"))
        assertEquals(0, compacted.patchCount)
        assertEquals(0, compacted.patchBytes)
    }

    @Test
    fun `sixteen KiB candidate compacts only after commit`() {
        val snapshots = mutableMapOf<String, MemorySnapshotState>()
        prepareMemorySnapshot(snapshots, "conversation", listOf(memory(1, "small")))
        val beforeCandidate = snapshots.getValue("conversation")
        val current = listOf(memory(1, "x".repeat(MEMORY_PATCH_COMPACTION_BYTES)))

        val resolution = prepareMemorySnapshot(snapshots, "conversation", current)
        val uncommitted = snapshots.getValue("conversation")

        assertEquals(beforeCandidate.cacheBlocks, uncommitted.cacheBlocks)
        assertEquals(beforeCandidate.patchCount, uncommitted.patchCount)
        assertEquals(beforeCandidate.patchBytes, uncommitted.patchBytes)
        assertEquals(beforeCandidate.revision, uncommitted.revision)
        assertEquals(1, resolution.commit!!.nextState.cacheBlocks.size)
        assertEquals(0, resolution.commit.nextState.patchBytes)

        commitMemorySnapshot(snapshots, resolution.commit, current)
        assertEquals(1, snapshots.getValue("conversation").cacheBlocks.size)
        assertEquals(0, snapshots.getValue("conversation").patchBytes)
    }

    @Test
    fun `empty initial projection has no Base and first successful creation establishes Base`() {
        val snapshots = mutableMapOf<String, MemorySnapshotState>()

        val empty = prepareMemorySnapshot(snapshots, "conversation", emptyList())
        assertEquals(0, empty.claudeCacheBlocks?.size)
        assertTrue(snapshots.getValue("conversation").cacheBlocks.isEmpty())

        val created = listOf(memory(1, "first"))
        val resolution = prepareMemorySnapshot(snapshots, "conversation", created)
        assertEquals(0, resolution.claudeCacheBlocks?.size)
        assertEquals(1, resolution.delta.size)
        assertEquals(1, resolution.commit!!.nextState.cacheBlocks.size)
        assertTrue(
            resolution.commit.nextState.cacheBlocks.single().text.trimStart()
                .startsWith("**Memory Base**")
        )

        commitMemorySnapshot(snapshots, resolution.commit, created)
        assertEquals(1, snapshots.getValue("conversation").cacheBlocks.size)
        assertEquals(0, snapshots.getValue("conversation").patchCount)
    }

    @Test
    fun `missing cache key safely falls back to complete current snapshot`() {
        val resolution = prepareMemorySnapshot(
            mutableMapOf(),
            null,
            listOf(memory(2, "newest"), memory(1, "older")),
        )

        assertEquals(listOf(2, 1), resolution.snapshot.map { it.id })
        assertEquals(null, resolution.claudeCacheBlocks)
        assertTrue(resolution.delta.isEmpty())
        assertEquals(null, resolution.commit)
    }

    @Test
    fun `Room refresh failure leaves successful provider result bookkeeping uncommitted`() = runBlocking {
        val snapshots = mutableMapOf<String, MemorySnapshotState>()
        val initial = listOf(memory(1, "initial"))
        val changed = listOf(memory(1, "changed"))
        prepareMemorySnapshot(snapshots, "conversation", initial)
        val resolution = prepareMemorySnapshot(snapshots, "conversation", changed)

        val committed = refreshAndCommitMemorySnapshot(
            memorySnapshots = snapshots,
            commit = resolution.commit!!,
            readLatestRoomMemories = { error("Room unavailable") },
            onRefreshFailure = {},
        )

        assertFalse(committed)
        assertEquals("initial", snapshots.getValue("conversation").snapshot.single().content)
    }

    @Test
    fun `Room refresh cancellation is propagated without invoking failure callback`() {
        val snapshots = mutableMapOf<String, MemorySnapshotState>()
        val initial = listOf(memory(1, "initial"))
        val changed = listOf(memory(1, "changed"))
        prepareMemorySnapshot(snapshots, "conversation", initial)
        val resolution = prepareMemorySnapshot(snapshots, "conversation", changed)
        var failureCallbackInvoked = false

        assertThrows(CancellationException::class.java) {
            runBlocking {
                refreshAndCommitMemorySnapshot(
                    memorySnapshots = snapshots,
                    commit = resolution.commit!!,
                    readLatestRoomMemories = { throw CancellationException("cancelled") },
                    onRefreshFailure = { failureCallbackInvoked = true },
                )
            }
        }

        assertFalse(failureCallbackInvoked)
        assertEquals("initial", snapshots.getValue("conversation").snapshot.single().content)
    }

    private fun assertChangeIsEmittedOnce(
        initial: List<AssistantMemory>,
        current: List<AssistantMemory>,
    ): String {
        val snapshots = mutableMapOf<String, MemorySnapshotState>()

        val baseline = prepareMemorySnapshot(snapshots, "conversation", initial)
        assertEquals(initial, baseline.snapshot)
        assertTrue(baseline.delta.isEmpty())

        val changed = prepareMemorySnapshot(snapshots, "conversation", current)
        assertEquals(current, changed.snapshot)
        assertEquals(1, changed.delta.size)
        commitMemorySnapshot(snapshots, changed.commit, current)

        val settled = prepareMemorySnapshot(snapshots, "conversation", current)
        assertEquals(current, settled.snapshot)
        assertTrue(settled.delta.isEmpty())
        return changed.delta.single().parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString(separator = "") { it.text }
    }

    private fun memory(id: Int, content: String) = AssistantMemory(
        id = id,
        content = content,
    )
}
