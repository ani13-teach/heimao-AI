package me.rerere.workspace.codemode

import androidx.annotation.Keep

/** Opaque sessions are owned by a single JS thread; only cancel is cross-thread safe. */
@Keep
object CodemodeNative {
    init {
        System.loadLibrary("workspace")
    }

    external fun attach(context: Any, milliseconds: Long, heapBytes: Long): Long
    external fun pump(handle: Long): Int
    external fun cancel(handle: Long)
    external fun detach(handle: Long)
}
