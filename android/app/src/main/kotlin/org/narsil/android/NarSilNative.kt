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
    const val META_CONTEXT = 12
    const val META_FOE_X = 13
    const val META_FOE_Y = 14
    const val META_FOE_HEALTH = 15
    const val META_FOE_STANCE = 16
    const val META_FOE_MORALE = 17
    const val META_NEW_EXP = 18
    const val META_COUNT = 19

    /** enum nb_stance, as sidebar text. */
    val STANCE_NAMES = arrayOf("", "Sleeping", "Unwary", "Mindless", "Fleeing", "Confident", "Aggressive")

    @JvmStatic external fun nativeFoeName(): String

    // enum nb_context
    const val CTX_UP_STAIRS = 1 shl 0
    const val CTX_DOWN_STAIRS = 1 shl 1
    const val CTX_FLOOR_ITEMS = 1 shl 2
    const val CTX_CAN_FIRE = 1 shl 3
    const val CTX_CAN_THROW = 1 shl 4
    const val CTX_STEALTH = 1 shl 5
    const val CTX_SINGING = 1 shl 6
    const val CTX_ADJ_CLOSED_DOOR = 1 shl 7
    const val CTX_ADJ_OPEN_DOOR = 1 shl 8
    const val CTX_ADJ_RUBBLE = 1 shl 9
    const val CTX_ADJ_TRAP = 1 shl 10
    const val CTX_FORGE = 1 shl 11

    // enum nb_action (order matters)
    const val ACT_REST = 0
    const val ACT_HOLD = 1
    const val ACT_GO_DOWN = 2
    const val ACT_GO_UP = 3
    const val ACT_PICKUP = 4
    const val ACT_STEALTH = 5
    const val ACT_SONG = 6
    const val ACT_FIRE_NEAREST = 7
    const val ACT_THROW_NEAREST = 8
    const val ACT_OPEN = 9
    const val ACT_CLOSE = 10
    const val ACT_DISARM = 11
    const val ACT_TUNNEL = 12
    const val ACT_BASH = 13
    const val ACT_EXCHANGE = 14
    const val ACT_INVENTORY = 15
    const val ACT_EQUIPMENT = 16
    const val ACT_MAP = 17
    const val ACT_LOOK = 18
    const val ACT_MESSAGES = 19
    const val ACT_CHARACTER = 20
    const val ACT_ABILITIES = 21
    const val ACT_HELP = 22
    const val ACT_SKILLS = 23
    const val ACT_CHARACTER_SCREEN = 24
    const val ACT_SMITH = 25
    const val ACT_OPTIONS = 26
    const val ACT_KNOWLEDGE = 27
    const val ACT_PANEL = 28

    /** Only honoured at the command prompt. */
    @JvmStatic external fun nativeRequestAction(action: Int)

    /** Read-only descriptions; see nb_request_inspect() in narsil_bridge.h for the format. */
    @JvmStatic external fun nativeRequestInspect(x: Int, y: Int)
    @JvmStatic external fun nativeRequestTarget(x: Int, y: Int)
    /** Rest: a number of turns, or REST_*. */
    @JvmStatic external fun nativeRequestRest(turnsOrMode: Int)
    const val REST_HP_AND_VOICE = -1
    const val REST_AS_NEEDED = -2
    const val REST_HP_OR_VOICE = -3
    @JvmStatic external fun nativeRequestLookList()
    @JvmStatic external fun nativeInfoGeneration(): Long
    @JvmStatic external fun nativeInfo(): String

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
    @JvmStatic external fun nativeRequestMove(dir: Int, run: Boolean)
    @JvmStatic external fun nativePathGeneration(): Long
    /** (x, y) pairs, target first; returns the pair count (0 = no path). */
    @JvmStatic external fun nativePath(xy: IntArray): Int

    const val PROMPT_NONE = 0
    const val PROMPT_ITEM = 1
    const val PROMPT_ACTIONS = 2
    const val PROMPT_CHECK = 3
    const val PROMPT_AIM = 4
    const val PROMPT_DIRECTION = 5
    const val PROMPT_CHOICE = 6
    const val PROMPT_SKILLS = 7
    const val PROMPT_ABILITIES = 8
    const val PROMPT_SMITH = 9
    const val PROMPT_TEXT = 10
    const val PROMPT_SCREEN = 11
    const val PROMPT_OPTIONS = 12
    const val PROMPT_PACK = 13
    const val PROMPT_KNOWLEDGE = 14
    const val PROMPT_CHARACTER = 15
    const val PROMPT_QUANTITY = 16
    /** The character panel (enum nb_panel_tab); while open, prompts also take PANEL_TAB + tab or PANEL_CLOSE. */
    const val TAB_PACK = 0
    const val TAB_CHARACTER = 1
    const val TAB_SKILLS = 2
    const val TAB_ABILITIES = 3
    const val TAB_KNOWLEDGE = 4
    const val TAB_SMITH = 5
    const val TAB_OPTIONS = 6
    const val TAB_COUNT = 7
    const val PANEL_TAB = 1 shl 24
    const val PANEL_CLOSE = PANEL_TAB + TAB_COUNT
    const val CHARACTER_SCREEN = 1
    const val CHARACTER_RENAME = 2
    const val CHARACTER_DUMP = 3
    /** The open panel tab, or -1. */
    @JvmStatic external fun nativePanelTab(): Int
    const val KNOW_COLLAPSE = 99999
    const val PACK_COLLAPSE = 999
    const val PACK_EXPAND = 1000
    const val PACK_ACT = 100000
    const val PACK_ACT_STRIDE = 100
    const val TEXT_RANDOM = 2
    const val SCREEN_RANDOM = 1000
    const val SCREEN_OPTIONS = 1001
    const val OPT_OFF = 0
    const val OPT_ON = 1
    const val OPT_MAYBE = 2
    const val OPT_CHOSEN = 3
    const val OPT_DEAR = 4
    const val OPT_ARTISTRY = 5
    const val ABILITY_LOCKED = 0
    const val ABILITY_AVAILABLE = 1
    const val ABILITY_ON = 2
    const val ABILITY_OFF = 3
    const val ABILITY_ITEM_ON = 4
    const val ABILITY_ITEM_OFF = 5

    const val SKILL_RESET = 1
    const val SKILL_ACCEPT = 2
    const val SKILL_BUY = 100
    const val SKILL_SELL = 200

    const val AIM_CURRENT = 100
    const val AIM_NEAREST = 101
    const val AIM_GRID_BASE = 1 shl 20
    const val AIM_GRID_STRIDE = 1024

    @JvmStatic external fun nativePromptGeneration(): Long
    /** "<kind>\n<text>"; see nb_prompt() in narsil_bridge.h for the item format. */
    @JvmStatic external fun nativePrompt(): String
    /** Index of the chosen item line, or -1 to cancel. */
    @JvmStatic external fun nativeAnswerItem(index: Int)
    /** Answer the open prompt; values per kind as in enum nb_prompt_kind. */
    @JvmStatic external fun nativeAnswer(value: Int)
    @JvmStatic external fun nativeAnswerText(text: String)
    @JvmStatic external fun nativeMessagesGeneration(): Long
    /** attr \t count \t text per line, oldest first. */
    @JvmStatic external fun nativeMessages(): String

    @JvmStatic external fun nativeState(): Int
    @JvmStatic external fun nativeExitMessage(): String
}
