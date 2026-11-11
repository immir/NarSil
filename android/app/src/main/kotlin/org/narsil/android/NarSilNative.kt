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
    /** button 1 = left, 2 = right; mods are KC_MOD_* flags. */
    @JvmStatic external fun nativePushMouse(x: Int, y: Int, button: Int, mods: Int)
    @JvmStatic external fun nativeRequestSave()
    @JvmStatic external fun nativeSaveCount(): Long
    @JvmStatic external fun nativeGeneration(): Long
    /** Fills [cells] with (codepoint, attr) pairs and [cursor] with (x, y, visible); returns the frame generation. */
    @JvmStatic external fun nativeSnapshot(cells: IntArray, cursor: IntArray): Long
    @JvmStatic external fun nativeCols(): Int
    @JvmStatic external fun nativeRows(): Int
    /** ARGB per Angband colour index. */
    @JvmStatic external fun nativePalette(): IntArray
    // Native map / commands: see narsil_bridge.h.  Meta indices mirror enum nb_meta.
    const val META_MAP_MODE = 0
    const val META_AT_PROMPT = 1
    const val META_WIDTH = 2
    const val META_HEIGHT = 3
    const val META_PLAYER_X = 4
    const val META_PLAYER_Y = 5
    const val META_HP = 6
    const val META_MAX_HP = 7
    const val META_VOICE = 8
    const val META_MAX_VOICE = 9
    const val META_DEPTH = 10
    const val META_EXP = 11
    const val META_COUNT = 12

    const val CELL_IN_VIEW = 1 shl 0
    const val CELL_PLAYER = 1 shl 1
    const val CELL_MONSTER = 1 shl 2
    const val CELL_OBJECT = 1 shl 3
    const val CELL_KNOWN = 1 shl 4

    @JvmStatic external fun nativeMapGeneration(): Long
    /** [cells] may be null (meta only); returns 0 if [cells] is too small for the level. */
    @JvmStatic external fun nativeMapSnapshot(meta: IntArray, cells: IntArray?): Long
    @JvmStatic external fun nativeRequestPreview(x: Int, y: Int)
    @JvmStatic external fun nativeClearPreview()
    @JvmStatic external fun nativeRequestTravel(x: Int, y: Int)
    @JvmStatic external fun nativePathGeneration(): Long
    /** (x, y) pairs, target first; returns the pair count (0 = no path). */
    @JvmStatic external fun nativePath(xy: IntArray): Int

    @JvmStatic external fun nativeState(): Int
    @JvmStatic external fun nativeExitMessage(): String
}
