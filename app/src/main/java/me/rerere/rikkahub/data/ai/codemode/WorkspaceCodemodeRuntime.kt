package me.rerere.rikkahub.data.ai.codemode

import android.util.Log
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.workspace.codemode.CodemodeNative
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object WorkspaceCodemodeRuntime {
    private const val CODE_BYTES = 32 * 1024
    private const val VALUE_BYTES = 64 * 1024
    private const val OUTPUT_BYTES = 32 * 1024
    private const val HEAP_BYTES = 16L * 1024 * 1024
    private const val STACK_BYTES = 512 * 1024
    private const val DEADLINE_MS = 120_000L
    private val probeMutex = Mutex()
    @Volatile private var available: Boolean? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "codemode-deadline").apply { isDaemon = true }
    }
    private val toolNames = setOf(
        "workspace_read_file", "workspace_write_file", "workspace_edit_file", "workspace_shell"
    )

    /** Fails closed on unsupported native libraries, job scheduling, or interrupt behavior. */
    suspend fun isAvailable(): Boolean = probeMutex.withLock {
        available?.let { return@withLock it }
        val result = try {
            val output = mutableListOf<JsonElement>()
            runIsolated("text(await Promise.resolve(7));", { _, _ -> JsonNull }, { output += it }, 2_000)
            check(output == listOf(JsonPrimitive(7)))
            val started = System.nanoTime()
            var interrupted = false
            try {
                runIsolated("while (true) {}", { _, _ -> JsonNull }, {}, 100, probeInterrupt = true)
            } catch (e: CancellationException) {
                // A probe timeout is local; caller cancellation must still propagate.
                kotlin.coroutines.coroutineContext.ensureActive()
                interrupted = false
            } catch (_: Exception) {
                interrupted = true
            }
            check(interrupted && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            true
        } catch (e: CancellationException) {
            kotlin.coroutines.coroutineContext.ensureActive()
            false
        } catch (error: Exception) {
            Log.w("WorkspaceCodemode", "Runtime capability probe failed", error)
            false
        } catch (error: LinkageError) {
            Log.w("WorkspaceCodemode", "Native runtime capability probe failed", error)
            false
        }
        available = result
        result
    }

    suspend fun execute(
        code: String,
        invoke: suspend (String, JsonObject) -> JsonElement,
        emit: suspend (JsonElement) -> Unit,
    ) {
        require(code.toByteArray(Charsets.UTF_8).size <= CODE_BYTES) { "Codemode code exceeds 32 KiB" }
        check(isAvailable()) { "Workspace Codemode runtime is unavailable" }
        runIsolated(code, invoke, emit, DEADLINE_MS)
    }

    private class Control {
        private var handle = 0L
        private var cancelled = false

        @Synchronized fun attach(value: Long) {
            handle = value
            if (cancelled) CodemodeNative.cancel(value)
        }

        @Synchronized fun cancel() {
            cancelled = true
            if (handle != 0L) CodemodeNative.cancel(handle)
        }

        @Synchronized fun detach() {
            if (handle != 0L) {
                CodemodeNative.detach(handle)
                handle = 0L
            }
        }
    }

    private suspend fun runIsolated(
        code: String,
        invoke: suspend (String, JsonObject) -> JsonElement,
        emit: suspend (JsonElement) -> Unit,
        timeoutMs: Long,
        probeInterrupt: Boolean = false,
    ): Unit = suspendCancellableCoroutine { continuation ->
        val parentContext = continuation.context
        val executionJob = Job(parentContext[Job])
        val control = Control()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "workspace-codemode").apply { isDaemon = true }
        }
        fun cancel(message: String) {
            control.cancel()
            executionJob.cancel(CancellationException(message))
        }
        continuation.invokeOnCancellation { cancel("Codemode cancelled") }
        val timer = watchdog.schedule({
            continuation.cancel(CancellationException("Codemode deadline exceeded"))
            cancel("Codemode deadline exceeded")
        }, if (probeInterrupt) 2_000 else timeoutMs, TimeUnit.MILLISECONDS)
        executor.execute {
            var failure: Throwable? = null
            var context: QuickJSContext? = null
            try {
                executionJob.ensureActive()
                System.loadLibrary("quickjs-android-wrapper")
                val js = QuickJSContext.create()
                context = js
                js.setMaxStackSize(STACK_BYTES)
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                check(remaining > 0) { "Codemode deadline exceeded" }
                val handle = CodemodeNative.attach(js, remaining, HEAP_BYTES)
                control.attach(handle)
                var completed = false
                var scriptFailure: String? = null
                var hostFailure: Throwable? = null
                var operations = 0
                var outputBytes = 0
                fun checkActive() {
                    executionJob.ensureActive()
                    check(System.nanoTime() < deadline) { "Codemode deadline exceeded" }
                    hostFailure?.let { throw it }
                }
                fun <T> bridge(block: suspend () -> T): T {
                    checkActive()
                    return runBlocking(parentContext.minusKey(ContinuationInterceptor) + executionJob) {
                        val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                        check(left > 0) { "Codemode deadline exceeded" }
                        withTimeout(left) { block() }
                    }.also { checkActive() }
                }
                js.globalObject.setProperty("__codemodeHost", JSCallFunction { args ->
                    try {
                        checkActive()
                        val kind = args.getOrNull(0) as? String ?: error("Invalid Codemode bridge operation")
                        val payload = args.getOrNull(1) as? String ?: error("Invalid Codemode bridge payload")
                        require(payload.toByteArray(Charsets.UTF_8).size <= VALUE_BYTES)
                        when (kind) {
                            "done" -> completed = true
                            "error" -> {
                                scriptFailure = payload.take(4096)
                                hostFailure = hostFailure ?: IllegalStateException("Codemode JavaScript error: $scriptFailure")
                                completed = true
                            }
                            "text" -> {
                                outputBytes += payload.toByteArray(Charsets.UTF_8).size
                                require(outputBytes <= OUTPUT_BYTES) { "Codemode output exceeds 32 KiB" }
                                bridge { emit(Json.parseToJsonElement(payload)) }
                            }
                            else -> {
                                require(kind in toolNames) { "Unknown Codemode tool" }
                                require(++operations <= 64) { "Codemode exceeds 64 operations" }
                                val argument = Json.parseToJsonElement(payload) as? JsonObject
                                    ?: error("Codemode tool argument must be an object")
                                val result = bridge { invoke(kind, argument) }.toString()
                                require(result.toByteArray(Charsets.UTF_8).size <= VALUE_BYTES) {
                                    "Codemode tool result exceeds 64 KiB"
                                }
                                return@JSCallFunction envelope(result)
                            }
                        }
                        envelope("null")
                    } catch (e: Throwable) {
                        hostFailure = hostFailure ?: e
                        // Do not throw across the wrapper's JNI callback boundary.
                        "{\"error\":\"Codemode host operation failed\"}"
                    }
                })
                val runner = js.evaluate(bootstrap(code), "workspace-codemode.js") as JSObject
                runner.getJSFunction("run").call()
                while (true) {
                    checkActive()
                    val pending = CodemodeNative.pump(handle)
                    if (pending == 0) break
                }
                checkActive()
                check(completed) { "Codemode script has an unresolved promise" }
                scriptFailure?.let { error("Codemode JavaScript error: $it") }
            } catch (e: Throwable) {
                failure = e
            } finally {
                // Interrupt handlers must be removed before wrapper destruction, on this thread.
                try {
                    control.detach()
                } catch (e: Throwable) {
                    failure = failure ?: e
                }
                try {
                    context?.destroy()
                } catch (e: Throwable) {
                    failure = failure ?: e
                }
                timer.cancel(false)
                executionJob.complete()
                executor.shutdown()
            }
            if (continuation.isActive) {
                val error = failure
                if (error == null) continuation.resume(Unit) else continuation.resumeWithException(error)
            }
        }
    }

    private fun envelope(value: String): String = buildJsonObject { put("value", value) }.toString()

    private fun bootstrap(code: String): String = """
        (() => {
            'use strict';
            const host = globalThis.__codemodeHost;
            delete globalThis.__codemodeHost;
            delete globalThis.console;
            // The wrapper reads this non-configurable property during destruction.
            // Keep a fixed, side-effect-free formatter even if user code mutates globals.
            Object.defineProperty(globalThis, 'format', {
                value: () => '', writable: false, configurable: false
            });
            const stringify = JSON.stringify;
            const parse = JSON.parse;
            const apply = Reflect.apply;
            const then = Promise.prototype.then;
            const ErrorType = Error;
            const charCodeAt = String.prototype.charCodeAt;
            const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
            let outputBytes = 0;
            let operations = 0;
            function bytes(s) {
                let n = 0;
                for (let i = 0; i < s.length; i++) {
                    const c = apply(charCodeAt, s, [i]);
                    if (c < 0x80) n++;
                    else if (c < 0x800) n += 2;
                    else if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length &&
                             apply(charCodeAt, s, [i + 1]) >= 0xdc00 &&
                             apply(charCodeAt, s, [i + 1]) <= 0xdfff) { n += 4; i++; }
                    else n += 3;
                }
                return n;
            }
            function call(name, payload) {
                const result = parse(host(name, payload));
                if (result.error) throw new ErrorType(result.error);
                return parse(result.value);
            }
            function encode(value, limit) {
                const s = stringify(value);
                if (typeof s !== 'string' || s.length > limit || bytes(s) > limit)
                    throw new ErrorType('Codemode JSON value exceeds byte limit or is not serializable');
                return s;
            }
            function report(error) {
                // Error strings must also be bounded before crossing JNI.
                let message = 'JavaScript failure';
                try {
                    if (typeof error === 'string') message = error;
                    else if (error && typeof error.message === 'string') message = error.message;
                    if (message.length > 1024) message = 'JavaScript error message exceeds limit';
                } catch (_) {}
                host('error', message);
            }
            function guarded(block) {
                try { return block(); }
                catch (error) { report(error); throw error; }
            }
            const tools = Object.create(null);
            for (const name of ['workspace_read_file', 'workspace_write_file', 'workspace_edit_file', 'workspace_shell']) {
                tools[name] = async function(args) {
                    return guarded(() => {
                        if (++operations > 64) throw new ErrorType('Codemode exceeds 64 operations');
                        const payload = encode(args, 65536);
                        if (payload[0] !== '{') throw new ErrorType('Tool argument must be an object');
                        return call(name, payload);
                    });
                };
            }
            Object.defineProperty(globalThis, 'tools', { value: Object.freeze(tools) });
            Object.defineProperty(globalThis, 'text', { value: function(value) {
                return guarded(() => {
                    const payload = encode(value, 32768);
                    outputBytes += bytes(payload);
                    if (outputBytes > 32768) throw new ErrorType('Codemode output exceeds 32 KiB');
                    call('text', payload);
                });
            }});
            return {
                run: () => {
                    try {
                        // User code receives a separate environment without host internals.
                        const run = new AsyncFunction(${JsonPrimitive(code)});
                        apply(then, run(), [() => host('done', ''), report]);
                    } catch (error) { report(error); }
                }
            };
        })();
    """.trimIndent()
}
