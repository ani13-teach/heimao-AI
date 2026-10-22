package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.workspace.workspaceBindMounts
import me.rerere.rikkahub.data.workspace.workspaceDnsServers
import me.rerere.rikkahub.data.repository.ContextBlockRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.workspace.WorkspaceAptExecutor
import me.rerere.rikkahub.data.workspace.WorkspaceRuntimeCoordinator
import me.rerere.rikkahub.data.workspace.RootfsPatchOptionsProvider
import me.rerere.workspace.KotlinWorkspaceShellRunner
import me.rerere.rikkahub.data.workspace.python.AndroidWorkspacePythonExecutor
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.RootfsPatchOptions
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single { ContextBlockRepository(get()) }
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single<RootfsPatchOptionsProvider> {
        val context: Context = get()
        RootfsPatchOptionsProvider {
            RootfsPatchOptions(nameservers = context.workspaceDnsServers())
        }
    }

    single {
        val context: Context = get()
        val rootfsPatchOptionsProvider: RootfsPatchOptionsProvider = get()
        // Keep file-tool path mappings available to the portable runner.
        val bindMounts = context.workspaceBindMounts()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = KotlinWorkspaceShellRunner(
                pythonExecutor = AndroidWorkspacePythonExecutor(context),
            ),
            bindMounts = bindMounts,
            rootfsPatchOptionsProvider = rootfsPatchOptionsProvider::invoke,
            portableRuntime = true,
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRuntimeCoordinator()
    }

    single {
        WorkspaceRepository(get(), get(), get(), get(), get(), get(), get(), get(), get())
    }

    // The plan service only needs the environment-side half of a plan; binding the interface keeps
    // its constructor free of the whole repository and lets tests supply a fake executor.
    single<WorkspaceAptExecutor> { get<WorkspaceRepository>() }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }
}
