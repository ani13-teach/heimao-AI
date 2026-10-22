package me.rerere.rikkahub.data.workspace

internal fun <T> retryArchiveSnapshot(
    attempts: Int, retryable: (Throwable) -> Boolean, operation: () -> T,
): T {
    require(attempts > 0)
    repeat(attempts) { attempt ->
        try {
            return operation()
        } catch (error: Throwable) {
            if (!retryable(error)) throw error
            if (attempt == attempts - 1) {
                throw IllegalStateException("Unable to create a stable Room database snapshot after retries", error)
            }
        }
    }
    error("Unreachable snapshot retry state")
}
