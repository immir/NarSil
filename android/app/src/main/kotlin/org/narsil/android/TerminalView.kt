package org.narsil.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Fallback renderer for any textui screen: draws the published Term grid and turns
 * IME / hardware key input into Angband keycodes.  Polls the bridge once per vsync
 * and only copies a frame when its generation changes.
 */
@SuppressLint("ViewConstructor")
class TerminalView(context: Context, private val router: KeyRouter) : View(context) {
    private val cols = NarSilNative.nativeCols()
    private val rows = NarSilNative.nativeRows()
    private val cells = IntArray(2 * cols * rows)
    private val cursor = IntArray(3)
    private var palette = IntArray(0)
    private var shownGeneration = 0L

    /** Bundled: the platform MONOSPACE typeface is proportional on some devices. */
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.dejavu_sans_mono) ?: Typeface.MONOSPACE
    }
    private val fillPaint = Paint()
    private val glyph = CharArray(2)
    private var cellW = 0f
    private var cellH = 0f
    private var baseline = 0f
    private var originX = 0f
    private var originY = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (NarSilNative.nativeGeneration() != shownGeneration) {
                shownGeneration = NarSilNative.nativeSnapshot(cells, cursor)
                palette = NarSilNative.nativePalette()
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(frameCallback)
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    /** Largest monospace font for which cols x rows cells fit; grid is centred. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val probe = 100f
        textPaint.textSize = probe
        val fm = textPaint.fontMetrics
        val probeW = widestGlyph()
        val probeH = fm.descent - fm.ascent
        val size = probe * minOf(w / (cols * probeW), h / (rows * probeH))
        textPaint.textSize = size
        val m = textPaint.fontMetrics
        cellW = widestGlyph()
        cellH = m.descent - m.ascent
        baseline = -m.ascent
        originX = (w - cols * cellW) / 2f
        originY = (h - rows * cellH) / 2f
    }

    /** Size cells to the widest ASCII glyph, in case the fallback typeface is proportional. */
    private fun widestGlyph(): Float = (0x21..0x7E).maxOf { textPaint.measureText(it.toChar().toString()) }

    private fun colour(index: Int): Int =
        if (index in palette.indices) palette[index] else Color.WHITE

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (palette.isEmpty()) return
        for (y in 0 until rows) {
            var x = 0
            while (x < cols) {
                val attr = cells[2 * (y * cols + x) + 1]
                val start = x
                while (x < cols && cells[2 * (y * cols + x) + 1] == attr) x++
                drawRun(canvas, start, y, x - start, attr)
            }
        }
        if (cursor[2] != 0) drawCursor(canvas, cursor[0], cursor[1])
    }

    /** attr = colour + 256 * background mode (z-color.h: BG_BLACK, BG_SAME, BG_DARK). */
    private fun drawRun(canvas: Canvas, x: Int, y: Int, n: Int, attr: Int) {
        val fg = colour(attr % 256 % MAX_COLORS)
        val left = originX + x * cellW
        val top = originY + y * cellH
        when (attr / 256) {
            BG_SAME -> fillPaint.color = fg
            BG_DARK -> fillPaint.color = colour(COLOUR_SHADE)
            else -> fillPaint.color = Color.TRANSPARENT
        }
        if (fillPaint.color != Color.TRANSPARENT)
            canvas.drawRect(left, top, left + n * cellW, top + cellH, fillPaint)
        textPaint.color = fg
        for (i in 0 until n) {
            val cp = cells[2 * (y * cols + x + i)]
            if (cp == ' '.code) continue
            val len = Character.toChars(cp, glyph, 0)
            val cx = left + i * cellW + (cellW - textPaint.measureText(glyph, 0, len)) / 2f
            canvas.drawText(glyph, 0, len, cx, top + baseline, textPaint)
        }
    }

    private fun drawCursor(canvas: Canvas, x: Int, y: Int) {
        fillPaint.color = Color.YELLOW
        val left = originX + x * cellW
        val bottom = originY + (y + 1) * cellH
        canvas.drawRect(left, bottom - cellH / 8f, left + cellW, bottom, fillPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) toggleKeyboard()
        return true
    }

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
        const val MAX_COLORS = 32
        const val COLOUR_SHADE = 28
        const val BG_SAME = 1
        const val BG_DARK = 2
    }
}
