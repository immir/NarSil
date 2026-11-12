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

    /** What a textui screen is waiting for, judged from its text; picks the key bar's buttons. */
    enum class Waiting { MENU, YES_NO, MORE, ANY_KEY, AT_SIGN }
    var waiting by mutableStateOf(Waiting.MENU)
        private set

    var prompt by mutableStateOf<Prompt?>(null)
        private set
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set

    data class Item(val index: Int, val section: Int, val label: Char, val glyph: Int, val attr: Int,
                    val weightTenthsLb: Int, val name: String)
    data class Action(val index: Int, val valid: Boolean, val label: String)
    data class Target(val x: Int, val y: Int, val inRange: Boolean, val name: String)
    data class Message(val attr: Int, val count: Int, val text: String)

    /** The game's open question; mirrors enum nb_prompt_kind. */
    sealed interface Prompt {
        data class Items(val title: String, val items: List<Item>) : Prompt
        data class Actions(val name: String, val glyph: Int, val attr: Int, val actions: List<Action>,
                           val description: String) : Prompt
        data class Check(val question: String) : Prompt
        data class Aim(val range: Int, val current: Pair<Int, Int>?, val targets: List<Target>) : Prompt
        data class Direction(val allowHere: Boolean, val neighbours: List<Neighbour>) : Prompt
        data class Choice(val title: String, val options: List<Option>) : Prompt
        data class Skills(val expLeft: Int, val skills: List<Skill>) : Prompt
        data class Smith(val view: String, val title: String, val notes: List<String>, val item: SmithItem?,
                         val costs: List<SmithCost>, val description: List<String>, val options: List<SmithOption>) : Prompt
        data class Text(val title: String, val initial: String, val maxBytes: Int, val random: Boolean) : Prompt
        data class Screen(val stage: String, val title: String, val paragraphs: List<String>, val options: List<ScreenOption>,
                          val stats: List<BirthStat>, val pointsLeft: Int, val pointsTotal: Int) : Prompt
        data class Abilities(val expLeft: Int, val skills: List<String>, val abilities: List<Ability>) : Prompt
    }

    data class Neighbour(val dir: Int, val x: Int, val y: Int, val what: String)
    data class Option(val index: Int, val label: String, val description: String)
    data class ScreenOption(val index: Int, val state: Int, val label: String, val summary: String, val description: String)
    data class BirthStat(val index: Int, val name: String, val value: Int, val spent: Int, val nextCost: Int,
                         val canSell: Boolean, val canBuy: Boolean)
    data class SmithItem(val name: String, val difficulty: Int, val max: Int, val drains: Boolean, val turns: Int, val turnsLeft: Int)
    data class SmithCost(val label: String, val state: Int)
    data class SmithOption(val index: Int, val state: Int, val label: String, val detail: String)
    data class Ability(val index: Int, val skill: Int, val state: Int, val points: Int, val cost: Int,
                       val prereqs: String, val name: String, val description: String)
    data class Skill(val index: Int, val name: String, val base: Int, val total: Int, val spent: Int, val nextCost: Int)

    /** Read-only descriptions the player asked for; dismissable, the game is not waiting on them. */
    sealed interface Info {
        data class Inspect(val x: Int, val y: Int, val monster: Monster?, val objects: List<String>, val terrain: String?) : Info
        data class LookList(val entries: List<LookEntry>) : Info
        data class Character(val sections: List<Section>) : Info
    }
    data class Monster(val name: String, val state: String, val recall: String)
    data class Section(val title: String, val rows: List<SheetRow>, val text: String?)
    data class SheetRow(val label: String, val value: String, val attr: Int, val detail: String)
    data class LookEntry(val x: Int, val y: Int, val summary: String)

    var info by mutableStateOf<Info?>(null)

    /** Glyph and attr the map shows at (x, y), or null off the map. */
    fun mapCell(x: Int, y: Int): Pair<Int, Int>? {
        val w = meta[N.META_WIDTH]
        val h = meta[N.META_HEIGHT]
        if (x !in 0 until w || y !in 0 until h) return null
        val i = 2 * (y * w + x)
        return map[i] to (map[i + 1] and 0xFFFF)
    }

    /** The monster being fought or targeted, as the textui sidebar tracks it. */
    data class Foe(val x: Int, val y: Int, val name: String, val healthPct: Int, val stance: Int, val morale: Int)

    data class Status(val hp: Int = 0, val maxHp: Int = 0, val voice: Int = 0, val maxVoice: Int = 0,
                      val depth: Int = 0, val exp: Int = 0, val context: Int = 0, val foe: Foe? = null,
                      val newExp: Int = 0) {
        fun has(flag: Int) = context and flag != 0
    }

    private var termGen = 0L
    private var mapGen = 0L
    private var pathGen = 0L
    private var promptGen = 0L
    private var messagesGen = 0L
    private var infoGen = -1L
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
            waiting = (0 until rows).map(::rowText).let { text ->
                fun any(re: Regex) = text.any { re.containsMatchIn(it) }
                when {
                    any(AT_SIGN) -> Waiting.AT_SIGN
                    any(YES_NO) -> Waiting.YES_NO
                    any(MORE) -> Waiting.MORE
                    any(ANY_KEY) -> Waiting.ANY_KEY
                    else -> Waiting.MENU
                }
            }
            termListeners.forEach { it() }
        }
        val mapChanged = pollMap()
        val pathChanged = N.nativePathGeneration().let { gen ->
            if (gen == pathGen) false else { pathGen = gen; pathPairs = N.nativePath(path); true }
        }
        if (mapChanged || pathChanged) mapListeners.forEach { it() }
        N.nativePromptGeneration().let { gen ->
            if (gen != promptGen) {
                promptGen = gen
                prompt = parsePrompt(N.nativePrompt())
                mapListeners.forEach { it() }  // the map draws aim targets
            }
        }
        N.nativeMessagesGeneration().let { gen -> if (gen != messagesGen) { messagesGen = gen; messages = parseMessages(N.nativeMessages()) } }
        N.nativeInfoGeneration().let { gen ->
            if (infoGen < 0) infoGen = gen  // ignore whatever was there before this UI existed
            else if (gen != infoGen) { infoGen = gen; info = parseInfo(N.nativeInfo()) }
        }
    }

    /** See nb_prompt() in narsil_bridge.h for each kind's layout. */
    private fun parsePrompt(raw: String): Prompt? {
        val kind = raw.substringBefore('\n').toIntOrNull() ?: return null
        val lines = raw.substringAfter('\n').split('\n')
        fun fields(line: String, n: Int) = line.split('\t', limit = n)
        return when (kind) {
            N.PROMPT_ITEM -> Prompt.Items(lines[0].trim(), lines.drop(1).filter { it.isNotEmpty() }.mapIndexed { i, line ->
                val f = fields(line, 6)
                Item(i, f[0].toInt(), f[1].first(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5])
            })
            N.PROMPT_ACTIONS -> {
                val split = lines.indexOf("--").takeIf { it >= 0 } ?: lines.size
                val (glyph, attr) = fields(lines[1], 2).map(String::toInt)
                Prompt.Actions(lines[0], glyph, attr,
                    lines.subList(2, split).mapIndexed { i, line -> fields(line, 2).let { Action(i, it[0] == "1", it[1]) } },
                    lines.drop(split + 1).joinToString("\n").trim())
            }
            N.PROMPT_CHECK -> Prompt.Check(lines.joinToString(" ").trim())
            N.PROMPT_AIM -> {
                val (range, cx, cy) = fields(lines[1], 3).map(String::toInt)
                Prompt.Aim(range, if (cx >= 0) cx to cy else null,
                    lines.drop(2).filter { it.isNotEmpty() }.map { fields(it, 4).let { f -> Target(f[0].toInt(), f[1].toInt(), f[2] == "1", f[3]) } })
            }
            N.PROMPT_CHOICE -> Prompt.Choice(lines[0].trim(), lines.drop(1).filter { it.isNotEmpty() }.mapIndexed { i, l ->
                fields(l, 2).let { f -> Option(i, f[0], f.getOrElse(1) { "" }) }
            })
            N.PROMPT_SKILLS -> Prompt.Skills(fields(lines[0], 2).getOrNull(1)?.toIntOrNull() ?: 0,
                lines.drop(1).filter { it.isNotEmpty() }.mapIndexed { i, l ->
                    fields(l, 5).let { f -> Skill(i, f[0], f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt()) }
                })
            N.PROMPT_SMITH -> {
                val head = lines[0].split('\t')
                val recs = lines.drop(1).filter { it.isNotEmpty() }.map { it.split('\t') }
                fun of(tag: String) = recs.filter { it[0] == tag }
                Prompt.Smith(head.getOrElse(1) { "" }, head.getOrElse(2) { "" },
                    of("#N").map { it.getOrElse(1) { "" } },
                    of("#I").firstOrNull()?.let { f -> SmithItem(f[1], f[2].toInt(), f[3].toInt(), f[4] == "1", f[5].toInt(), f[6].toInt()) },
                    of("#C").map { SmithCost(it[1], it[2].toInt()) },
                    of("#D").map { it.getOrElse(1) { "" } },
                    of("#O").mapIndexed { i, f -> SmithOption(i, f[1].toInt(), f[2], f.getOrElse(3) { "" }) })
            }
            N.PROMPT_TEXT -> Prompt.Text(lines[0], lines.getOrElse(1) { "" }, lines.getOrNull(2)?.toIntOrNull() ?: 19,
                                         lines.getOrNull(3) == "1")
            N.PROMPT_SCREEN -> {
                val head = lines[0].split('\t')
                val recs = lines.drop(1).filter { it.isNotEmpty() }.map { it.split('\t') }
                fun of(tag: String) = recs.filter { it[0] == tag }
                val points = of("#P").firstOrNull()
                Prompt.Screen(head.getOrElse(1) { "" }, head.getOrElse(2) { "" },
                    of("#H").map { it.getOrElse(1) { "" } },
                    of("#O").mapIndexed { i, f -> ScreenOption(i, f[1].toInt(), f[2], f.getOrElse(3) { "" }, f.getOrElse(4) { "" }) },
                    of("#S").mapIndexed { i, f -> BirthStat(i, f[1], f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5] == "1", f[6] == "1") },
                    points?.get(1)?.toInt() ?: 0, points?.get(2)?.toInt() ?: 0)
            }
            N.PROMPT_ABILITIES -> Prompt.Abilities(fields(lines[0], 2).getOrNull(1)?.toIntOrNull() ?: 0, lines[1].split('\t'),
                lines.drop(2).filter { it.isNotEmpty() }.mapIndexed { i, l ->
                    fields(l, 7).let { f -> Ability(i, f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4], f[5], f.getOrElse(6) { "" }) }
                })
            N.PROMPT_DIRECTION -> Prompt.Direction(lines.getOrNull(1)?.trim() == "1",
                lines.drop(2).filter { it.isNotEmpty() }.map { fields(it, 4).let { f -> Neighbour(f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3]) } })
            else -> null
        }
    }

    /** See nb_request_inspect() in narsil_bridge.h. */
    private fun parseInfo(raw: String): Info? {
        val lines = raw.split('\n')
        val head = lines.firstOrNull()?.split('\t') ?: return null
        return when (head[0]) {
            "Look" -> Info.LookList(lines.drop(1).filter { it.isNotEmpty() }.map { l ->
                l.split('\t', limit = 3).let { LookEntry(it[0].toInt(), it[1].toInt(), it[2]) }
            })
            "Character" -> Info.Character(buildList {
                lines.drop(1).filter { it.isNotEmpty() }.forEach { l ->
                    val f = l.split('\t')
                    when (f[0]) {
                        "#S" -> add(Section(f.getOrElse(1) { "" }, emptyList(), null))
                        "#T" -> if (isNotEmpty()) set(lastIndex, last().copy(text = f.getOrElse(1) { "" }))
                        else -> if (isNotEmpty() && f.size >= 3) set(lastIndex, last().copy(rows = last().rows +
                            SheetRow(f[0], f[1], f[2].toIntOrNull() ?: 1, f.getOrElse(3) { "" })))
                    }
                }
            })
            "Inspect" -> {
                var monster: Monster? = null
                val objects = mutableListOf<String>()
                var terrain: String? = null
                var i = 1
                while (i < lines.size) {
                    val f = lines[i].split('\t')
                    when (f[0]) {
                        "#M" -> {
                            val body = lines.drop(i + 1).takeWhile { !it.startsWith("#") }
                            monster = Monster(f.getOrElse(1) { "" }, f.getOrElse(2) { "" }, body.joinToString("\n").trim())
                            i += body.size
                        }
                        "#O" -> objects += f.getOrElse(1) { "" }
                        "#F" -> terrain = f.getOrElse(1) { "" }
                    }
                    i++
                }
                Info.Inspect(head[1].toInt(), head[2].toInt(), monster, objects, terrain)
            }
            else -> null
        }
    }

    /** Drops the textui's blank and "=====" separator lines. */
    private fun parseMessages(raw: String): List<Message> = raw.split('\n').mapNotNull { line ->
        val f = line.split('\t', limit = 3)
        val text = f.getOrNull(2)?.trim().orEmpty()
        if (text.isEmpty() || text.all { it == '=' }) null else Message(f[0].toInt(), f[1].toInt(), text)
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
                        meta[N.META_DEPTH], meta[N.META_EXP], meta[N.META_CONTEXT],
                        if (meta[N.META_FOE_X] < 0) null else Foe(meta[N.META_FOE_X], meta[N.META_FOE_Y], N.nativeFoeName(),
                            meta[N.META_FOE_HEALTH], meta[N.META_FOE_STANCE], meta[N.META_FOE_MORALE]),
                        meta[N.META_NEW_EXP])
        return true
    }

    private fun rowText(y: Int): String = buildString(cols) {
        for (x in 0 until cols) appendCodePoint(term[2 * (y * cols + x)])
    }.trim()

    private companion object {
        val YES_NO = Regex("""\[y/n\]""", RegexOption.IGNORE_CASE)
        val MORE = Regex("-more-")
        val AT_SIGN = Regex("by typing the '@'")
        val ANY_KEY = Regex("any key", RegexOption.IGNORE_CASE)
    }

    fun colour(index: Int): Int = palette.getOrElse(index) { android.graphics.Color.WHITE }
}
