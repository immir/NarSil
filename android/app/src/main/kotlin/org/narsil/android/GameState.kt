package org.narsil.android

import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.narsil.android.NarSilNative as N

/**
 * The UI thread's copy of everything the game publishes, refreshed once per vsync
 * and only when a generation counter moves.  Views register for invalidation;
 * Compose reads the observable fields.
 */
class GameState {
    val cols = N.nativeCols()
    val rows = N.nativeRows()

    val term = IntArray(2 * cols * rows)
    val termCursor = IntArray(3)
    var palette = IntArray(0)
        private set

    val meta = IntArray(N.META_COUNT)
    var map = IntArray(0)
        private set
    val path = IntArray(2 * 600)
    var pathPairs = 0
        private set

    var mapMode by mutableStateOf(false)
        private set
    var atPrompt by mutableStateOf(false)
        private set
    var status by mutableStateOf(Status())
        private set
    var messageLine by mutableStateOf("")
        private set
    var statusLine by mutableStateOf("")
        private set

    var itemPrompt by mutableStateOf<ItemPrompt?>(null)
        private set

    data class Item(val index: Int, val section: Int, val label: Char, val glyph: Int, val attr: Int,
                    val weightTenthsLb: Int, val name: String)
    data class ItemPrompt(val title: String, val items: List<Item>)

    data class Status(val hp: Int = 0, val maxHp: Int = 0, val voice: Int = 0, val maxVoice: Int = 0,
                      val depth: Int = 0, val exp: Int = 0)

    private var termGen = 0L
    private var mapGen = 0L
    private var pathGen = 0L
    private var promptGen = 0L
    private val termListeners = mutableListOf<() -> Unit>()
    private val mapListeners = mutableListOf<() -> Unit>()

    fun onTermChanged(f: () -> Unit) { termListeners += f }
    fun onMapChanged(f: () -> Unit) { mapListeners += f }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            poll()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() = Choreographer.getInstance().postFrameCallback(frame)
    fun stop() = Choreographer.getInstance().removeFrameCallback(frame)

    private fun poll() {
        if (N.nativeGeneration() != termGen) {
            termGen = N.nativeSnapshot(term, termCursor)
            palette = N.nativePalette()
            messageLine = rowText(0)
            statusLine = rowText(rows - 1)
            termListeners.forEach { it() }
        }
        val mapChanged = pollMap()
        val pathChanged = N.nativePathGeneration().let { gen ->
            if (gen == pathGen) false else { pathGen = gen; pathPairs = N.nativePath(path); true }
        }
        if (mapChanged || pathChanged) mapListeners.forEach { it() }
        N.nativePromptGeneration().let { gen -> if (gen != promptGen) { promptGen = gen; itemPrompt = parsePrompt(N.nativePrompt()) } }
    }

    /** See nb_prompt(): kind line, prompt line, then tab-separated item records. */
    private fun parsePrompt(raw: String): ItemPrompt? {
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.toIntOrNull() != N.PROMPT_ITEM) return null
        val items = lines.drop(2).filter { it.isNotEmpty() }.mapIndexed { i, line ->
            val f = line.split('\t', limit = 6)
            Item(i, f[0].toInt(), f[1].first(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5])
        }
        return ItemPrompt(lines.getOrElse(1) { "" }.trim(), items)
    }

    private fun pollMap(): Boolean {
        if (N.nativeMapGeneration() == mapGen) return false
        var gen = N.nativeMapSnapshot(meta, map)
        if (gen == 0L) {
            map = IntArray(2 * meta[N.META_WIDTH] * meta[N.META_HEIGHT])
            gen = N.nativeMapSnapshot(meta, map)
        }
        mapGen = gen
        mapMode = meta[N.META_MAP_MODE] != 0
        atPrompt = meta[N.META_AT_PROMPT] != 0
        status = Status(meta[N.META_HP], meta[N.META_MAX_HP], meta[N.META_VOICE], meta[N.META_MAX_VOICE],
                        meta[N.META_DEPTH], meta[N.META_EXP])
        return true
    }

    private fun rowText(y: Int): String = buildString(cols) {
        for (x in 0 until cols) appendCodePoint(term[2 * (y * cols + x)])
    }.trim()

    fun colour(index: Int): Int = palette.getOrElse(index) { android.graphics.Color.WHITE }
}
