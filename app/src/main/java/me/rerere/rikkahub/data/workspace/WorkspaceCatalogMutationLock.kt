package me.rerere.rikkahub.data.workspace

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes workspace catalog mutations with assistant binding validation and cleanup. */
class WorkspaceCatalogMutationLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
