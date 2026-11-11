package org.narsil.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Draws Angband (codepoint, attr) cells on a fixed grid.  Shared by the terminal
 * fallback and the native map so both render glyphs identically.
 */
class GlyphPainter(context: Context, private val colour: (Int) -> Int) {
    /** Bundled: the platform MONOSPACE typeface is proportional on some devices. */
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.dejavu_sans_mono) ?: Typeface.MONOSPACE
    }
    private val fillPaint = Paint()
    private val glyph = CharArray(2)

    var cellW = 0f
        private set
    var cellH = 0f
        private set
    private var baseline = 0f

    /** Probe metrics at 100px: width of the widest ASCII glyph and line height. */
    val probe: Pair<Float, Float> by lazy {
        textPaint.textSize = PROBE
        val fm = textPaint.fontMetrics
        widestGlyph() to (fm.descent - fm.ascent)
    }

    /** Largest text size whose cells fit within (w, h). */
    fun fitCells(cols: Int, rows: Int, w: Float, h: Float) {
        val (pw, ph) = probe
        setTextSize(PROBE * minOf(w / (cols * pw), h / (rows * ph)))
    }

    /** Text size for a requested cell height. */
    fun setCellHeight(px: Float) = setTextSize(PROBE * px / probe.second)

    private fun setTextSize(size: Float) {
        textPaint.textSize = size
        val m = textPaint.fontMetrics
        cellW = widestGlyph()
        cellH = m.descent - m.ascent
        baseline = -m.ascent
    }

    /** Size cells to the widest ASCII glyph, in case the fallback typeface is proportional. */
    private fun widestGlyph(): Float = (0x21..0x7E).maxOf { textPaint.measureText(it.toChar().toString()) }

    /** attr = colour + 256 * background mode (z-color.h: BG_BLACK, BG_SAME, BG_DARK). */
    fun drawCell(canvas: Canvas, left: Float, top: Float, cp: Int, attr: Int) {
        val fg = colour(attr % 256 % MAX_COLORS)
        val bg = when (attr / 256) {
            BG_SAME -> fg
            BG_DARK -> colour(COLOUR_SHADE)
            else -> Color.TRANSPARENT
        }
        if (bg != Color.TRANSPARENT) {
            fillPaint.color = bg
            canvas.drawRect(left, top, left + cellW, top + cellH, fillPaint)
        }
        if (cp == ' '.code || cp == 0) return
        textPaint.color = fg
        val len = Character.toChars(cp, glyph, 0)
        val x = left + (cellW - textPaint.measureText(glyph, 0, len)) / 2f
        canvas.drawText(glyph, 0, len, x, top + baseline, textPaint)
    }

    private companion object {
        const val PROBE = 100f
        const val MAX_COLORS = 32
        const val COLOUR_SHADE = 28
        const val BG_SAME = 1
        const val BG_DARK = 2
    }
}
