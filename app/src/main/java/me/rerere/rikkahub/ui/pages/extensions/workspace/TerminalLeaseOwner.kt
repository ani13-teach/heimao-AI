package me.rerere.rikkahub.ui.pages.extensions.workspace

/** Minimal process surface needed to coordinate a terminal lease without Android dependencies. */
internal interface TerminalLeaseProcess {
    val pid: Int
    fun finish()
}

internal enum class TerminalProcessState {
    NOT_STARTED,
    RUNNING,
    EXITED,
}

internal fun terminalProcessState(pid: Int): TerminalProcessState = when {
    pid == 0 -> TerminalProcessState.NOT_STARTED
    pid > 0 -> TerminalProcessState.RUNNING
    else -> TerminalProcessState.EXITED
}

/**
 * Owns one terminal attempt from preparation through process exit.
 *
 * Cleanup can be registered before a lease or process exists. A running process keeps its lease
 * until its finish callback; a process that never started (or already exited) releases immediately.
 */
internal class TerminalLeaseOwner {
    private var lease: AutoCloseable? = null
    private var process: TerminalLeaseProcess? = null
    private var cleanupRequested = false
    private var preparationComplete = false
    private var released = false

    fun attachLease(newLease: AutoCloseable) {
        val closeImmediately = synchronized(this) {
            check(lease == null) { "A terminal lease is already attached" }
            if (released || (cleanupRequested && preparationComplete)) {
                true
            } else {
                lease = newLease
                false
            }
        }
        if (closeImmediately) runCatching { newLease.close() }
    }

    fun attachProcess(newProcess: TerminalLeaseProcess) {
        val cleanupImmediately = synchronized(this) {
            check(process == null) { "A terminal process is already attached" }
            check(preparationComplete) { "A terminal process cannot attach before preparation completes" }
            process = newProcess
            cleanupRequested
        }
        if (cleanupImmediately) cleanupProcess(newProcess)
    }

    fun preparationSucceeded() {
        val releaseNow = synchronized(this) {
            preparationComplete = true
            cleanupRequested && process == null
        }
        if (releaseNow) releaseLease()
    }

    fun releaseAfterPreparationFailure() {
        synchronized(this) { preparationComplete = true }
        releaseLease()
    }

    fun onProcessFinished() {
        releaseLease()
    }

    fun cleanup() {
        val currentProcess = synchronized(this) {
            if (cleanupRequested) return
            cleanupRequested = true
            process
        }
        if (!synchronized(this) { preparationComplete }) return
        if (currentProcess == null) {
            releaseLease()
        } else {
            cleanupProcess(currentProcess)
        }
    }

    private fun cleanupProcess(currentProcess: TerminalLeaseProcess) {
        when (terminalProcessState(currentProcess.pid)) {
            TerminalProcessState.RUNNING -> currentProcess.finish()
            TerminalProcessState.NOT_STARTED,
            TerminalProcessState.EXITED,
            -> releaseLease()
        }
    }

    private fun releaseLease() {
        val currentLease = synchronized(this) {
            if (released) return
            released = true
            lease.also { lease = null }
        }
        if (currentLease != null) runCatching { currentLease.close() }
    }
}
