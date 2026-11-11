package org.narsil.android

/** Thin JNI surface over narsil_bridge.h; see that header for the threading contract. */
object NarSilNative {
    init {
        System.loadLibrary("narsil")
    }

    const val STATE_IDLE = 0
    const val STATE_RUNNING = 1
    const val STATE_EXITED = 2

    @JvmStatic external fun nativeStart(libDir: String, dataDir: String, playerName: String, cols: Int, rows: Int): Boolean
    @JvmStatic external fun nativePushKey(code: Int, mods: Int)
    @JvmStatic external fun nativeRequestSave()
    @JvmStatic external fun nativeSaveCount(): Long
    @JvmStatic external fun nativeGeneration(): Long
    /** Fills [cells] with (codepoint, attr) pairs and [cursor] with (x, y, visible); returns the frame generation. */
    @JvmStatic external fun nativeSnapshot(cells: IntArray, cursor: IntArray): Long
    @JvmStatic external fun nativeCols(): Int
    @JvmStatic external fun nativeRows(): Int
    /** ARGB per Angband colour index. */
    @JvmStatic external fun nativePalette(): IntArray
    @JvmStatic external fun nativeState(): Int
    @JvmStatic external fun nativeExitMessage(): String
}
