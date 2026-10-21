#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>

namespace {
using Clock = std::chrono::steady_clock;
struct Api {
    void* library = nullptr;
    void (*memory)(void*, size_t) = nullptr;
    void (*interrupt)(void*, int (*)(void*, void*), void*) = nullptr;
    int (*pending)(void*) = nullptr;
    int (*execute)(void*, void**) = nullptr;
    void (*modules)(void*, void*, void*, void*) = nullptr;
    bool ready = false;
    Api() {
        library = dlopen("libquickjs-android-wrapper.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) return;
        memory = reinterpret_cast<decltype(memory)>(dlsym(library, "JS_SetMemoryLimit"));
        interrupt = reinterpret_cast<decltype(interrupt)>(dlsym(library, "JS_SetInterruptHandler"));
        pending = reinterpret_cast<decltype(pending)>(dlsym(library, "JS_IsJobPending"));
        execute = reinterpret_cast<decltype(execute)>(dlsym(library, "JS_ExecutePendingJob"));
        modules = reinterpret_cast<decltype(modules)>(dlsym(library, "JS_SetModuleLoaderFunc"));
        ready = memory && interrupt && pending && execute && modules;
        // Retain the library for the lifetime of its function pointers.
    }
};
Api& api() { static Api value; return value; }
struct Session {
    void* runtime;
    Clock::time_point deadline;
    std::thread::id owner;
    std::atomic<bool> cancelled{false};
    Session(void* rt, jlong milliseconds) : runtime(rt),
        deadline(Clock::now() + std::chrono::milliseconds(milliseconds)),
        owner(std::this_thread::get_id()) {}
    bool stopped() const { return cancelled.load() || Clock::now() >= deadline; }
};
std::mutex sessionsMutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
jlong nextId = 1;
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::shared_ptr<Session> lookup(jlong id) {
    std::lock_guard<std::mutex> lock(sessionsMutex);
    auto entry = sessions.find(id);
    return entry == sessions.end() ? nullptr : entry->second;
}
int interrupt(void*, void* opaque) { return static_cast<Session*>(opaque)->stopped() ? 1 : 0; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_rerere_workspace_codemode_CodemodeNative_attach(JNIEnv* env, jobject, jobject context,
                                                       jlong milliseconds, jlong heapBytes) {
    if (!api().ready || milliseconds <= 0 || heapBytes <= 0) {
        fail(env, "Codemode QuickJS native capabilities unavailable");
        return 0;
    }
    jclass cls = env->GetObjectClass(context);
    jfieldID rtField = env->GetFieldID(cls, "runtime", "J");
    if (!rtField) return 0;
    jfieldID ctxField = env->GetFieldID(cls, "context", "J");
    if (!ctxField) return 0;
    // wrapper 3.2.3 stores JSRuntime* in runtime, but context is QuickJSWrapper*,
    // not JSContext*. Do not pass the wrapper object to QuickJS C functions.
    auto rt = reinterpret_cast<void*>(static_cast<uintptr_t>(env->GetLongField(context, rtField)));
    const jlong wrapper = env->GetLongField(context, ctxField);
    if (!rt || !wrapper) {
        fail(env, "Codemode QuickJS runtime is not initialized");
        return 0;
    }
    auto session = std::make_shared<Session>(rt, milliseconds);
    jlong id;
    {
        std::lock_guard<std::mutex> lock(sessionsMutex);
        id = nextId++;
        sessions.emplace(id, session);
    }
    api().memory(rt, static_cast<size_t>(heapBytes));
    api().modules(rt, nullptr, nullptr, nullptr);
    api().interrupt(rt, interrupt, session.get());
    return id;
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_workspace_codemode_CodemodeNative_cancel(JNIEnv*, jobject, jlong id) {
    auto session = lookup(id);
    if (session) session->cancelled.store(true);
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_workspace_codemode_CodemodeNative_pump(JNIEnv* env, jobject, jlong id) {
    auto session = lookup(id);
    if (!session || session->owner != std::this_thread::get_id()) {
        fail(env, "Codemode job pump called outside its JS thread");
        return -1;
    }
    if (session->stopped()) {
        fail(env, "Codemode execution cancelled or deadline exceeded");
        return -1;
    }
    if (!api().pending(session->runtime)) return 0;
    void* context = nullptr;
    int result = api().execute(session->runtime, &context);
    if (result < 0) fail(env, "Codemode pending JavaScript job failed");
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_workspace_codemode_CodemodeNative_detach(JNIEnv* env, jobject, jlong id) {
    auto session = lookup(id);
    if (!session || session->owner != std::this_thread::get_id()) {
        fail(env, "Codemode detach called outside its JS thread");
        return;
    }
    api().interrupt(session->runtime, nullptr, nullptr);
    // Script execution has ended. Permit the wrapper's final bookkeeping after an OOM.
    api().memory(session->runtime, static_cast<size_t>(-1));
    std::lock_guard<std::mutex> lock(sessionsMutex);
    sessions.erase(id);
}
