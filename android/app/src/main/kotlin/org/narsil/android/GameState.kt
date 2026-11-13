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
    /** The character panel tab the prompt belongs to, or -1. */
    var panelTab by mutableStateOf(-1)
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
        /** The whole pack; [expanded] (an item index, or -1) shows its [actions] and [description]. */
        data class Pack(val expanded: Int, val focus: Int, val weight: Int, val limit: Int, val items: List<Item>,
                        val actions: List<Action>, val description: String, val resumed: Boolean) : Prompt
        data class Check(val question: String) : Prompt
        data class Aim(val range: Int, val current: Pair<Int, Int>?, val targets: List<Target>) : Prompt
        data class Direction(val allowHere: Boolean, val neighbours: List<Neighbour>) : Prompt
        data class Choice(val title: String, val options: List<Option>) : Prompt
        data class Skills(val expLeft: Int, val skills: List<Skill>) : Prompt
        data class Smith(val view: String, val title: String, val notes: List<String>, val item: SmithItem?,
                         val costs: List<SmithCost>, val description: List<String>, val options: List<SmithOption>) : Prompt
        /** The knowledge browser: [rows] in order; [expanded] (an entry index or -1) has [text]. */
        data class Knowledge(val view: String, val title: String, val expanded: Int, val rows: List<KnowRow>, val text: String,
                             val actions: List<Pair<Int, String>> = emptyList()) : Prompt
        /** How many (e.g. of a stack to drop): 0..[max], starting at [initial]. */
        data class Quantity(val title: String, val max: Int, val initial: Int) : Prompt
        /** The panel's Character tab. */
        data class Character(val info: Info.Character) : Prompt
        data class Options(val page: String, val title: String, val rows: List<OptionRow>) : Prompt
        data class Text(val title: String, val initial: String, val maxBytes: Int, val random: Boolean) : Prompt
        data class Screen(val stage: String, val title: String, val paragraphs: List<String>, val options: List<ScreenOption>,
                          val stats: List<BirthStat>, val pointsLeft: Int, val pointsTotal: Int,
                          val skills: List<Pair<String, String>> = emptyList(), val combat: List<Pair<String, String>> = emptyList(),
                          val verse: List<String> = emptyList()) : Prompt
        data class Abilities(val expLeft: Int, val skills: List<AbilitySkill>, val abilities: List<Ability>) : Prompt
    }

    data class Neighbour(val dir: Int, val x: Int, val y: Int, val what: String)
    data class Option(val index: Int, val label: String, val description: String)
    sealed interface KnowRow {
        data class Group(val title: String) : KnowRow
        data class Entry(val index: Int, val glyph: Int, val attr: Int, val name: String, val note: String) : KnowRow
    }
    /** A line of the options screen (narsil_options.c). */
    sealed interface OptionRow {
        data class Heading(val text: String) : OptionRow
        data class Toggle(val answer: Int, val on: Boolean, val locked: Boolean, val description: String, val name: String) : OptionRow
        data class Number(val base: Int, val label: String, val value: Int, val min: Int, val max: Int, val detail: String) : OptionRow
        data class Link(val answer: Int, val label: String, val detail: String) : OptionRow
    }
    data class ScreenOption(val index: Int, val state: Int, val label: String, val summary: String, val description: String)
    data class BirthStat(val index: Int, val name: String, val value: Int, val spent: Int, val nextCost: Int,
                         val canSell: Boolean, val canBuy: Boolean)
    data class SmithItem(val name: String, val difficulty: Int, val max: Int, val drains: Boolean, val turns: Int, val turnsLeft: Int)
    data class SmithCost(val label: String, val state: Int)
    data class SmithOption(val index: Int, val state: Int, val label: String, val detail: String)
    data class Ability(val index: Int, val skill: Int, val state: Int, val points: Int, val cost: Int,
                       val prereqs: String, val name: String, val description: String)
    /** A skill tab: abilities need [base] points (without stat or equipment modifiers). */
    data class AbilitySkill(val name: String, val base: Int, val total: Int)
    data class Skill(val index: Int, val name: String, val base: Int, val total: Int, val spent: Int, val nextCost: Int)

    /** Read-only descriptions the player asked for; dismissable, the game is not waiting on them. */
    sealed interface Info {
        data class Inspect(val x: Int, val y: Int, val monster: Monster?, val objects: List<String>, val terrain: String?,
                           val targetable: Boolean = false, val isTarget: Boolean = false) : Info
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
        val i = N.MAP_CELL_INTS * (y * w + x)
        return map[i] to (map[i + 1] and 0xFFFF)
    }

    /** The monster being fought or targeted, as the textui sidebar tracks it. */
    data class Foe(val x: Int, val y: Int, val name: String, val healthPct: Int, val stance: Int, val morale: Int)

    data class Status(val hp: Int = 0, val maxHp: Int = 0, val voice: Int = 0, val maxVoice: Int = 0,
                      val depth: Int = 0, val exp: Int = 0, val context: Int = 0, val foe: Foe? = null,
                      val newExp: Int = 0, val hpWarn: Int = 0) {
        fun has(flag: Int) = context and flag != 0
        /** Below the game's hitpoint warning (tenths of maximum health). */
        val lowHp get() = maxHp > 0 && hpWarn > 0 && hp * 10 < maxHp * hpWarn
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

    /** Polls once per display frame while started; idempotent. */
    fun start() = Choreographer.getInstance().run { removeFrameCallback(frame); postFrameCallback(frame) }
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
                prompt = Parse.prompt(N.nativePrompt())
                panelTab = N.nativePanelTab()
                mapListeners.forEach { it() }  // the map draws aim targets
            }
        }
        N.nativeMessagesGeneration().let { gen -> if (gen != messagesGen) { messagesGen = gen; messages = Parse.messages(N.nativeMessages()) } }
        N.nativeInfoGeneration().let { gen ->
            if (infoGen < 0) infoGen = gen  // ignore whatever was there before this UI existed
            else if (gen != infoGen) { infoGen = gen; info = Parse.info(N.nativeInfo()) }
        }
    }

    private fun pollMap(): Boolean {
        if (N.nativeMapGeneration() == mapGen) return false
        var gen = N.nativeMapSnapshot(meta, map)
        if (gen == 0L) {
            map = IntArray(N.MAP_CELL_INTS * meta[N.META_WIDTH] * meta[N.META_HEIGHT])
            gen = N.nativeMapSnapshot(meta, map)
        }
        mapGen = gen
        mapMode = meta[N.META_MAP_MODE] != 0
        atPrompt = meta[N.META_AT_PROMPT] != 0
        status = Status(meta[N.META_HP], meta[N.META_MAX_HP], meta[N.META_VOICE], meta[N.META_MAX_VOICE],
                        meta[N.META_DEPTH], meta[N.META_EXP], meta[N.META_CONTEXT],
                        if (meta[N.META_FOE_X] < 0) null else Foe(meta[N.META_FOE_X], meta[N.META_FOE_Y], N.nativeFoeName(),
                            meta[N.META_FOE_HEALTH], meta[N.META_FOE_STANCE], meta[N.META_FOE_MORALE]),
                        meta[N.META_NEW_EXP], meta[N.META_HP_WARN])
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
