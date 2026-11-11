package org.narsil.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import org.narsil.android.NarSilNative as N

/**
 * Native, zoomable map of the whole level, drawn from the game's own glyphs.
 *
 *  - tap an adjacent grid: step there
 *  - tap a distant grid: ghost path (from the game's pathfinder); tap it again to go
 *  - long press + drag: inspect / move the ghost path with the finger
 *  - swipe: one step in that direction (also answers direction prompts)
 *  - pinch: zoom; two-finger drag: pan (the camera follows the player again once they move)
 */
@SuppressLint("ViewConstructor")
class MapView(context: Context, private val state: GameState, private val router: KeyRouter) : View(context) {
    private val painter = GlyphPainter(context, state::colour)
    private val density = resources.displayMetrics.density
    private var cellHeightPx = DEFAULT_CELL_DP * density
        set(v) { field = v.coerceIn(MIN_CELL_DP * density, MAX_CELL_DP * density); painter.setCellHeight(field) }

    /** Grid coordinate shown at the view centre. */
    private var camX = 0f
    private var camY = 0f
    private var followPlayer = true
    private var lastPlayer = -1 to -1
    private var camAnimator: ValueAnimator? = null

    private var selected: Pair<Int, Int>? = null
    private var inspecting = false
    private var multiTouch = false

    private val pathPaint = Paint().apply { color = Color.argb(90, 255, 200, 60) }
    private val targetPaint = Paint().apply { color = Color.rgb(255, 200, 60); style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val playerPaint = Paint().apply { color = Color.argb(140, 120, 200, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f * density }

    init {
        painter.setCellHeight(cellHeightPx)
        state.onMapChanged { onMapChanged() }
    }

    private val mapW get() = state.meta[N.META_WIDTH]
    private val mapH get() = state.meta[N.META_HEIGHT]
    private val player get() = state.meta[N.META_PLAYER_X] to state.meta[N.META_PLAYER_Y]

    private fun onMapChanged() {
        if (player != lastPlayer) {
            val first = lastPlayer.first < 0
            lastPlayer = player
            followPlayer = true
            if (selected == player) selected = null
            keepPlayerInView(animate = !first)
        }
        if (isShown) invalidate()
    }

    /** Recentre when the player leaves the middle half of the view. */
    private fun keepPlayerInView(animate: Boolean) {
        if (!followPlayer || painter.cellW <= 0f || width == 0) return
        val (px, py) = player
        val halfW = width / painter.cellW / 2f
        val halfH = height / painter.cellH / 2f
        val outside = abs(px + 0.5f - camX) > halfW * 0.5f || abs(py + 0.5f - camY) > halfH * 0.5f
        if (!outside && animate) return
        val (tx, ty) = px + 0.5f to py + 0.5f
        camAnimator?.cancel()
        if (!animate) { camX = tx; camY = ty; return }
        val (sx, sy) = camX to camY
        camAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                camX = sx + (tx - sx) * t
                camY = sy + (ty - sy) * t
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = keepPlayerInView(animate = false)

    private fun originX() = width / 2f - camX * painter.cellW
    private fun originY() = height / 2f - camY * painter.cellH

    private fun gridAt(px: Float, py: Float): Pair<Int, Int>? {
        val x = floor((px - originX()) / painter.cellW).toInt()
        val y = floor((py - originY()) / painter.cellH).toInt()
        return if (x in 0 until mapW && y in 0 until mapH) x to y else null
    }

    override fun onDraw(canvas: Canvas) {
        /* Compose's AndroidView does not clip children: drawColor() would paint the whole window. */
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(Color.BLACK)
        val w = mapW
        val h = mapH
        if (w == 0 || state.palette.isEmpty()) return
        val cw = painter.cellW
        val ch = painter.cellH
        val ox = originX()
        val oy = originY()
        val x0 = maxOf(0, floor(-ox / cw).toInt())
        val y0 = maxOf(0, floor(-oy / ch).toInt())
        val x1 = minOf(w - 1, floor((width - ox) / cw).toInt())
        val y1 = minOf(h - 1, floor((height - oy) / ch).toInt())
        val map = state.map
        for (y in y0..y1) for (x in x0..x1) {
            val i = 2 * (y * w + x)
            painter.drawCell(canvas, ox + x * cw, oy + y * ch, map[i], map[i + 1] and 0xFFFF)
        }
        drawPath(canvas, ox, oy, cw, ch)
        val (px, py) = player
        canvas.drawRect(ox + px * cw, oy + py * ch, ox + (px + 1) * cw, oy + (py + 1) * ch, playerPaint)
    }

    private fun drawPath(canvas: Canvas, ox: Float, oy: Float, cw: Float, ch: Float) {
        val path = state.path
        for (k in 1 until state.pathPairs) {
            val x = path[2 * k]
            val y = path[2 * k + 1]
            canvas.drawRect(ox + x * cw, oy + y * ch, ox + (x + 1) * cw, oy + (y + 1) * ch, pathPaint)
        }
        selected?.let { (x, y) ->
            canvas.drawRect(ox + x * cw, oy + y * ch, ox + (x + 1) * cw, oy + (y + 1) * ch, targetPaint)
        }
    }

    private fun select(g: Pair<Int, Int>?) {
        selected = g
        if (g == null) N.nativeClearPreview() else N.nativeRequestPreview(g.first, g.second)
        invalidate()
    }

    private fun onTap(g: Pair<Int, Int>) {
        if (!state.atPrompt) return
        val (px, py) = player
        val adjacent = abs(g.first - px) <= 1 && abs(g.second - py) <= 1 && g != player
        when {
            adjacent -> { select(null); N.nativeRequestTravel(g.first, g.second) }
            g == selected -> { selected = null; N.nativeRequestTravel(g.first, g.second) }
            else -> select(g)
        }
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!multiTouch) gridAt(e.x, e.y)?.let(::onTap)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (multiTouch || !state.atPrompt) return
            gridAt(e.x, e.y)?.let {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                inspecting = true
                select(it)
            }
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            if (multiTouch || inspecting) return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            if (hypot(dx, dy) < 2f * painter.cellW) return false
            router.direction(octant(dx, dy))
            return true
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            cellHeightPx *= d.scaleFactor
            invalidate()
            return true
        }
    })

    private var lastFocus = 0f to 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { multiTouch = false; inspecting = false }
            MotionEvent.ACTION_POINTER_DOWN -> { multiTouch = true; lastFocus = focus(e) }
            MotionEvent.ACTION_POINTER_UP -> lastFocus = focus(e, excluding = e.actionIndex)
            MotionEvent.ACTION_MOVE -> when {
                multiTouch && e.pointerCount >= 2 -> pan(e)
                inspecting -> gridAt(e.x, e.y)?.takeIf { it != selected }?.let(::select)
            }
        }
        if (!inspecting || e.actionMasked != MotionEvent.ACTION_MOVE) gestures.onTouchEvent(e)
        return true
    }

    /** Mean pointer position, optionally ignoring a pointer that is lifting. */
    private fun focus(e: MotionEvent, excluding: Int = -1): Pair<Float, Float> {
        val ids = (0 until e.pointerCount).filter { it != excluding }
        return ids.map { e.getX(it) }.average().toFloat() to ids.map { e.getY(it) }.average().toFloat()
    }

    private fun pan(e: MotionEvent) {
        val f = focus(e)
        camAnimator?.cancel()
        followPlayer = false
        camX -= (f.first - lastFocus.first) / painter.cellW
        camY -= (f.second - lastFocus.second) / painter.cellH
        lastFocus = f
        invalidate()
    }

    private companion object {
        const val DEFAULT_CELL_DP = 22f
        const val MIN_CELL_DP = 8f
        const val MAX_CELL_DP = 64f
    }
}
