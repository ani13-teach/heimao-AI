package me.rerere.rikkahub.data.workspace

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.db.entity.WorkspaceAptPlanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Explicit opt-in only: leaves a disposable workspace in the dedicated emulator for inspection. */
@RunWith(AndroidJUnit4::class)
class IsolatedEmulatorAptSmokeTest {
    @Test
    fun installRealX86RootfsAndSimulateSmallPackage() = runBlocking<Unit> {
        requireIsolatedEmulator()
        val repository = GlobalContext.get().get<WorkspaceRepository>()
        val workspace = repository.getAll().firstOrNull { it.name == "Apt emulator sandbox" }
            ?: repository.create("Apt emulator sandbox")
        assertTrue(repository.isShared(workspace.id))
        if (!repository.sharedEnvironmentInfo().ready) {
            repository.installRootfs(
                workspace.id,
                "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-amd64.tar.gz",
            )
        }
        assertTrue(repository.sharedEnvironmentInfo().ready)
        val update = repository.executeCommand(workspace.id, "apt-get update", timeoutMillis = 180_000L)
        Log.i("AptEmulatorSmoke", "apt update exit=${update.exitCode}, stderr=${update.stderr.take(500)}")
        assertEquals("Unable to prepare package lists: ${update.stderr.take(500)}", 0, update.exitCode)
        val service = GlobalContext.get().get<WorkspaceAptPlanService>()
        val plan = service.createPlan(
            workspace.id,
            "emulator-assistant",
            "emulator-conversation",
            WorkspaceAptRequest.install(listOf("sl")),
        )
        Log.i("AptEmulatorSmoke", "plan=${plan.planId} changes=${plan.summaryJson}")
        assertTrue(plan.summaryJson.contains("sl"))
        assertTrue(service.pendingPlan(plan.planId, plan.planFingerprint) != null)
        val toolCallId = "isolated-emulator-approval"
        assertTrue(runCatching {
            service.execute(plan.planId, workspace.id, plan.assistantId, plan.conversationId,
                toolCallId, plan.planFingerprint)
        }.isFailure)
        assertTrue(runCatching {
            service.confirm(plan.planId, workspace.id, plan.assistantId, plan.conversationId,
                toolCallId, "wrong-fingerprint")
        }.isFailure)
        service.confirm(plan.planId, workspace.id, plan.assistantId, plan.conversationId,
            toolCallId, plan.planFingerprint)
        val outcome = service.execute(plan.planId, workspace.id, plan.assistantId,
            plan.conversationId, toolCallId, plan.planFingerprint)
        Log.i("AptEmulatorSmoke", "execution state=${outcome.state} exit=${outcome.exitCode}")
        assertEquals(WorkspaceAptPlanState.SUCCEEDED, outcome.state)
        assertFalse(repository.isSharedAptBlocked)
        assertTrue(repository.sharedEnvironmentInfo().recoveryAvailable)
        assertTrue(runCatching {
            service.execute(plan.planId, workspace.id, plan.assistantId, plan.conversationId,
                toolCallId, plan.planFingerprint)
        }.isFailure)
        val installed = repository.executeCommand(workspace.id, "dpkg-query -W -f='${'$'}{Status}' sl")
        assertEquals("sl was not installed: ${'$'}{installed.stderr}", 0, installed.exitCode)
        assertTrue(installed.stdout.contains("installed"))
        assertTrue("Recovery point was not restored", repository.restoreSharedRecovery())
        val rolledBack = repository.executeCommand(workspace.id, "dpkg-query -W -f='${'$'}{Status}' sl")
        assertTrue("Package remained after recovery", rolledBack.exitCode != 0 ||
            !rolledBack.stdout.contains("installed"))
        Log.i("AptEmulatorSmoke", "recovery restored original package state")
    }

    @Test
    fun restoredEnvironmentRemainsUsableAfterApplicationRestart() = runBlocking<Unit> {
        requireIsolatedEmulator()
        val repository = GlobalContext.get().get<WorkspaceRepository>()
        val workspace = repository.getAll().single { it.name == "Apt emulator sandbox" }
        assertTrue(repository.isShared(workspace.id))
        assertTrue(repository.sharedEnvironmentInfo().ready)
        assertFalse(repository.refreshSharedAptGate())
        assertTrue(repository.blockingSharedAptPlans().isEmpty())
        val status = repository.executeCommand(workspace.id, "dpkg-query -W -f='${'$'}{Status}' sl")
        assertTrue(status.exitCode != 0 || !status.stdout.contains("installed"))
    }

    private fun requireIsolatedEmulator() {
        val name = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("getprop ro.boot.qemu.avd_name").let { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
            }
        assumeTrue(
            "Only the disposable heimao_apt_test emulator may change its shared Rootfs",
            Build.FINGERPRINT.contains("sdk_gphone") &&
                Build.SUPPORTED_ABIS.contains("x86_64") &&
                name == "heimao_apt_test" &&
                InstrumentationRegistry.getArguments().getString("aptSandbox") == name,
        )
    }
}
