package org.narsil.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.InputType
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Fallback renderer for any textui screen: draws the published Term grid from
 * [state] and turns IME / hardware key input and gestures into Angband input.
 */
@SuppressLint("ViewConstructor")
class TerminalView(context: Context, private val state: GameState, private val router: KeyRouter) : View(context) {
    private val cols = state.cols
    private val rows = state.rows
    private val painter = GlyphPainter(context, state::colour)
    private val cursorPaint = Paint().apply { color = Color.YELLOW }
    private val cellW get() = painter.cellW
    private val cellH get() = painter.cellH
    private var originX = 0f
    private var originY = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        state.onTermChanged { if (isShown) invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    /** Largest font for which cols x rows cells fit; grid is centred. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        painter.fitCells(cols, rows, w.toFloat(), h.toFloat())
        originX = (w - cols * cellW) / 2f
        originY = (h - rows * cellH) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        /* Compose's AndroidView does not clip children: drawColor() would paint the whole window. */
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(Color.BLACK)
        if (state.palette.isEmpty()) return
        val cells = state.term
        for (y in 0 until rows) for (x in 0 until cols) {
            val i = 2 * (y * cols + x)
            painter.drawCell(canvas, originX + x * cellW, originY + y * cellH, cells[i], cells[i + 1])
        }
        val cursor = state.termCursor
        if (cursor[2] != 0) {
            val left = originX + cursor[0] * cellW
            val bottom = originY + (cursor[1] + 1) * cellH
            canvas.drawRect(left, bottom - cellH / 8f, left + cellW, bottom, cursorPaint)
        }
    }

    /** Screen point -> term cell, or null outside the grid. */
    private fun cellAt(px: Float, py: Float): Pair<Int, Int>? {
        if (cellW <= 0f || cellH <= 0f) return null
        val x = floor((px - originX) / cellW).toInt()
        val y = floor((py - originY) / cellH).toInt()
        return if (x in 0 until cols && y in 0 until rows) x to y else null
    }

    /**
     * Tap = left click (walk/pathfind, select menu item); long press = right click
     * (context menu, target); fling = a direction (arrows for cardinals, so menus
     * scroll; keypad digits for diagonals).
     */
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            cellAt(e.x, e.y)?.let { (x, y) -> router.mouse(x, y, MOUSE_LEFT) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            cellAt(e.x, e.y)?.let { (x, y) ->
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                router.mouse(x, y, MOUSE_RIGHT)
            }
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            if (hypot(dx, dy) < 2f * cellW) return false
            router.direction(octant(dx, dy))
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    fun toggleKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        requestFocus()
        val shown = ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (shown) imm.hideSoftInputFromWindow(windowToken, 0) else imm.showSoftInput(this, 0)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                text.codePoints().forEach { cp -> if (cp == '\n'.code) router.special(Keys.ENTER) else router.char(cp) }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(maxOf(beforeLength, 1)) { router.special(Keys.BACKSPACE) }
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val special = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Keys.ENTER
            KeyEvent.KEYCODE_ESCAPE -> Keys.ESCAPE
            KeyEvent.KEYCODE_DEL -> Keys.BACKSPACE
            KeyEvent.KEYCODE_TAB -> Keys.TAB
            KeyEvent.KEYCODE_DPAD_UP -> Keys.ARROW_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Keys.ARROW_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Keys.ARROW_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Keys.ARROW_RIGHT
            else -> null
        }
        if (special != null) {
            router.special(special)
            return true
        }
        val cp = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
        if (cp == 0) return super.onKeyDown(keyCode, event)
        if (event.isCtrlPressed) router.control(cp) else router.char(cp)
        return true
    }

    private companion object {
        const val MOUSE_LEFT = 1
        const val MOUSE_RIGHT = 2

    }
}
