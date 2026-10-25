package me.rerere.rikkahub.data.workspace.python

import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.workspace.WorkspaceShellContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AndroidWorkspacePythonExecutorTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val executor = AndroidWorkspacePythonExecutor(app)
    private lateinit var directory: File
    private lateinit var shell: WorkspaceShellContext

    @Before fun setUp() {
        directory = File(app.noBackupFilesDir, "python-test-${UUID.randomUUID()}").apply { mkdirs() }
        val files = File(directory, "files").apply { mkdirs() }
        val cwd = File(files, "nested").apply { mkdirs() }
        shell = WorkspaceShellContext(
            root = directory.path, command = "", cwd = "/workspace/nested",
            filesDir = files, linuxDir = File(directory, "linux"),
            tempDir = File(directory, "tmp").apply { mkdirs() }, workingDir = cwd,
            homeDir = File(directory, "home"), timeoutMillis = 120_000,
        )
    }

    @After fun tearDown() { directory.deleteRecursively() }

    private fun run(code: String, timeout: Long = 120_000) =
        executor.execute(shell, listOf("-c", code), null, timeout)

    @Test fun scientificLibrariesExcelAndChinesePlot() {
        val result = run("""
            import numpy as np, pandas as pd, openpyxl, matplotlib, warnings
            from matplotlib import pyplot as plt
            assert np.__version__ == '1.26.2'
            assert pd.__version__ == '2.1.3'
            assert openpyxl.__version__ == '3.1.5'
            assert matplotlib.__version__ == '3.8.4'
            assert matplotlib.get_backend().lower() == 'agg'
            matrix = np.arange(9).reshape(3, 3)
            assert int((matrix @ matrix)[2, 2]) == 111
            coefficients = np.array([[3., 1.], [1., 2.]])
            solution = np.linalg.solve(coefficients, np.array([9., 8.]))
            assert np.allclose(solution, [2., 3.])
            grouped = pd.DataFrame({'组': ['甲', '乙', '甲'], '值': [2, 5, 7]})
            totals = grouped.groupby('组', as_index=False)['值'].sum()
            merged = totals.merge(pd.DataFrame({'组': ['甲', '乙'], '标签': ['A', 'B']}), on='组')
            assert merged.set_index('标签')['值'].to_dict() == {'A': 9, 'B': 5}
            df = pd.DataFrame({'项目': ['合计'], '数值': [12]})
            df.to_excel('/workspace/result.xlsx', index=False)
            loaded = pd.read_excel('/workspace/result.xlsx')
            assert loaded.iloc[0, 1] == 12
            workbook = openpyxl.Workbook()
            workbook.active.append(['中文项目', 42])
            workbook.save('/workspace/direct.xlsx')
            workbook.close()
            restored = openpyxl.load_workbook('/workspace/direct.xlsx')
            assert restored.active['A1'].value == '中文项目'
            assert restored.active['B1'].value == 42
            restored.close()
            with warnings.catch_warnings():
                warnings.filterwarnings('error', message='Glyph .* missing from font.*')
                fig, ax = plt.subplots()
                ax.imshow(matrix)
                ax.set_title('中文图表数据')
                ax.set_xlabel('列')
                ax.set_ylabel('行')
                fig.savefig('/workspace/plot.png')
                plt.close(fig)
            from PIL import Image, ImageStat
            with Image.open('/workspace/plot.png') as image:
                image.load()
                assert image.format == 'PNG'
                assert image.width > 100 and image.height > 100
                assert max(ImageStat.Stat(image.convert('RGB')).var) > 1
            print('ok')
        """.trimIndent())
        assertEquals(result.stderr, 0, result.exitCode)
        assertEquals("ok\n", result.stdout)
        assertTrue(File(shell.filesDir, "result.xlsx").length() > 1000)
        assertTrue(File(shell.filesDir, "plot.png").length() > 1000)
    }

    @Test fun scriptArgumentsStdinAndVirtualPaths() {
        File(shell.workingDir, "script.py").writeText("""
            import os, sys
            from pathlib import Path
            assert os.getcwd() == sys.argv[1]
            assert sys.stdin.read() == 'input'
            Path('/workspace/value.txt').write_text('workspace')
            Path('/root/home.txt').write_text('home')
            Path('/tmp/temp.txt').write_text('temp')
            assert Path('/workspace/value.txt').read_text() == 'workspace'
            assert Path('/root/home.txt').read_text() == 'home'
            assert Path('/tmp/temp.txt').read_text() == 'temp'
            print(sys.argv[2])
        """.trimIndent())
        val result = executor.execute(shell,
            listOf("script.py", shell.workingDir.canonicalPath, "argument"),
            "input".toByteArray(), 120_000)
        assertEquals(result.stderr, 0, result.exitCode)
        assertEquals("argument\n", result.stdout)
        val stdinResult = executor.execute(shell, emptyList(), "print('stdin program')".toByteArray(), 120_000)
        assertEquals(stdinResult.stderr, 0, stdinResult.exitCode)
        assertEquals("stdin program\n", stdinResult.stdout)
    }

    @Test fun exceptionsExitStatusAndBoundedOutput() {
        val error = run("raise ValueError('expected')")
        assertEquals(1, error.exitCode)
        assertTrue(error.stderr.contains("ValueError: expected"))
        assertEquals(7, run("raise SystemExit(7)").exitCode)
        val output = run("import sys; print('x' * 300000); sys.stderr.write('e' * 300000)")
        assertEquals(0, output.exitCode)
        assertEquals(128 * 1024, output.stdout.length)
        assertEquals(128 * 1024, output.stderr.length)
        assertTrue(output.truncated)
    }

    @Test fun timeoutKillsWorkerAndNextExecutionColdStarts() {
        val marker = File(shell.filesDir, "started")
        val result = run("from pathlib import Path; Path('/workspace/started').touch();\nwhile True: pass", 20_000)
        assertTrue(result.timedOut)
        assertFalse(result.terminationPending)
        assertTrue("Worker did not reach script before timeout", marker.exists())
        val next = run("print('restarted')")
        assertEquals(next.stderr, 0, next.exitCode)
        assertEquals("restarted\n", next.stdout)
    }

    @Test fun threadInterruptionKillsWorkerAndAllowsRestart() {
        val outcome = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val marker = File(shell.filesDir, "running")
        val thread = Thread {
            try {
                run("from pathlib import Path; Path('/workspace/running').touch();\nwhile True: pass")
                outcome.set(AssertionError("Execution was not interrupted"))
            } catch (error: Throwable) { outcome.set(error) }
            finally { done.countDown() }
        }.apply { start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (!marker.exists() && System.nanoTime() < deadline && done.count > 0) Thread.sleep(50)
        val started = marker.exists()
        thread.interrupt()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue("Worker did not reach script", started)
        assertTrue(outcome.get().toString(), outcome.get() is InterruptedException)
        val next = run("print('after cancel')")
        assertEquals(next.stderr, 0, next.exitCode)
        assertEquals("after cancel\n", next.stdout)
    }

    @Test fun executorInstancesSerializeAcrossWorkspaces() {
        val first = AndroidWorkspacePythonExecutor(app)
        val second = AndroidWorkspacePythonExecutor(app)
        val errors = AtomicReference<Throwable?>()
        val marker = File(shell.filesDir, "first-started")
        val thread = Thread {
            try {
                val result = first.execute(shell, listOf("-c",
                    "from pathlib import Path; import time; Path('/workspace/first-started').touch(); time.sleep(1); Path('/workspace/first-done').touch()"),
                    null, 120_000)
                check(result.exitCode == 0) { result.stderr }
            } catch (error: Throwable) { errors.set(error) }
        }.apply { start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (!marker.exists() && thread.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
        val result = second.execute(shell, listOf("-c",
            "from pathlib import Path; assert Path('/workspace/first-done').exists()"), null, 120_000)
        thread.join(130_000)
        assertFalse(thread.isAlive)
        assertNull(errors.get())
        assertEquals(result.stderr, 0, result.exitCode)
    }
}
