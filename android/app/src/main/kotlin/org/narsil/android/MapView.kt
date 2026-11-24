package org.narsil.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
 *  - long press: describe the grid (monster recall, objects, terrain)
 *  - swipe: by option, pan the map, or one step or a run in that direction; always answers aim and direction prompts
 *  - pinch: zoom; two-finger drag: pan (the camera follows the player again once they move)
 */
@SuppressLint("ViewConstructor")
class MapView(context: Context, private val state: GameState, private val router: KeyRouter,
              private val swipeMode: () -> Int, private val tiles: () -> Boolean,
              private val padShown: () -> Boolean) : View(context) {
    init { contentDescription = "Dungeon map. Tap a square to preview the path, tap it again to walk there. Long-press to describe what is there." }
    private val painter = GlyphPainter(context, state::colour)
    /** MicroChasm's 16x16 tile sheet (lib/tiles/microchasm; NarSil-Q's overlay has Sil-Q's), drawn unsmoothed. */
    private val tileSheet: Bitmap? = runCatching {
        val lib = if (GameLauncher.game(context) == GameLauncher.Game.SILQ) "lib-silq" else "lib"
        context.assets.open("$lib/tiles/microchasm/16x16.png").use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
    /** The sheet tinted as Sil-Q tints it while the player rages: each pixel's luma becomes (1, 0.3, 0.3) of it. */
    private val rageSheet: Bitmap? by lazy {
        tileSheet?.let { src ->
            val luma = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f)
            val dim = luma.map { it * 0.3f }.toFloatArray()
            val tint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(luma + dim + dim + floatArrayOf(0f, 0f, 0f, 1f, 0f))) }
            Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also { Canvas(it).drawBitmap(src, 0f, 0f, tint) }
        }
    }
    private val tilePaint = Paint().apply { isFilterBitmap = false }
    private val tileSrc = Rect()
    private val tileDst = RectF()
    private val tilesOn get() = tiles() && tileSheet != null
    /* Tiles are square; glyph cells are as wide as the widest glyph. */
    private val cellW get() = if (tilesOn) painter.cellH else painter.cellW
    private val cellH get() = painter.cellH
    private val density = resources.displayMetrics.density
    private var cellHeightPx = DEFAULT_CELL_DP * density
        set(v) { field = v.coerceIn(minCellPx, MAX_CELL_DP * density); painter.setCellHeight(field) }
    /* Lowered while the whole level is shown (see toggleOverview). */
    private var minCellPx = MIN_CELL_DP * density
    private var overviewFrom: Float? = null

    /** Grid coordinate shown at the view centre. */
    private var camX = 0f
    private var camY = 0f
    private var followPlayer = true
    private var lastPlayer = -1 to -1
    private var camAnimator: ValueAnimator? = null

    private var selected: Pair<Int, Int>? = null
    private var multiTouch = false

    private val pathPaint = Paint().apply { color = Color.argb(90, 255, 200, 60) }
    private val targetPaint = Paint().apply { color = Color.rgb(255, 200, 60); style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val targetRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 90, 80); style = Paint.Style.STROKE; strokeWidth = 2.5f * density }
    private val playerPaint = Paint().apply { color = Color.argb(140, 120, 200, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f * density }

    init {
        painter.setCellHeight(cellHeightPx)
        state.onMapChanged { onMapChanged() }
    }

    private val mapW get() = state.meta[N.META_WIDTH]
    private val mapH get() = state.meta[N.META_HEIGHT]
    private val player get() = state.meta[N.META_PLAYER_X] to state.meta[N.META_PLAYER_Y]

    /**
     * The whole level at once (☰ → Map), or back to the zoom it came from.  Moving
     * also ends it.
     */
    fun toggleOverview() {
        val from = overviewFrom
        if (from != null) { endOverview(from); return }
        if (mapW <= 0 || mapH <= 0 || width == 0 || cellH <= 0f) return
        overviewFrom = cellHeightPx
        val aspect = cellW / cellH
        val fit = minOf(height.toFloat() / mapH, width.toFloat() / (mapW * aspect))
        minCellPx = minOf(MIN_CELL_DP * density, fit)
        cellHeightPx = fit
        camAnimator?.cancel()
        followPlayer = false
        camX = mapW / 2f
        camY = mapH / 2f
        invalidate()
    }

    private fun endOverview(from: Float) {
        overviewFrom = null
        minCellPx = MIN_CELL_DP * density
        cellHeightPx = from
        followPlayer = true
        keepPlayerInView(animate = false)
        invalidate()
    }

    /** The tracked foe as last drawn, and a brief red fill on its square when its health drops (no animation: on, then off). */
    private var lastFoe: GameState.Foe? = null
    private var flash: Pair<Int, Int>? = null
    private val flashPaint = Paint().apply { color = Color.argb(115, 255, 60, 50) }

    private fun noteFoe() {
        val f = state.status.foe
        val was = lastFoe
        lastFoe = f
        if (f == null || was == null || f.name != was.name || f.healthPct >= was.healthPct) return
        flash = f.x to f.y
        postDelayed({ flash = null; invalidate() }, FLASH_MS)
    }

    private fun onMapChanged() {
        noteFoe()
        overviewFrom?.let { from -> if (player != lastPlayer && lastPlayer.first >= 0) endOverview(from) }
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
        if (!followPlayer || cellW <= 0f || width == 0) return
        val (px, py) = player
        val halfW = width / cellW / 2f
        val halfH = height / cellH / 2f
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

    private fun originX() = width / 2f - camX * cellW
    private fun originY() = height / 2f - camY * cellH

    private fun gridAt(px: Float, py: Float): Pair<Int, Int>? {
        val x = floor((px - originX()) / cellW).toInt()
        val y = floor((py - originY()) / cellH).toInt()
        return if (x in 0 until mapW && y in 0 until mapH) x to y else null
    }

    override fun onDraw(canvas: Canvas) {
        /* Compose's AndroidView does not clip children: drawColor() would paint the whole window. */
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(Color.BLACK)
        val w = mapW
        val h = mapH
        if (w == 0 || state.palette.isEmpty()) return
        val cw = cellW
        val ch = cellH
        val ox = originX()
        val oy = originY()
        val x0 = maxOf(0, floor(-ox / cw).toInt())
        val y0 = maxOf(0, floor(-oy / ch).toInt())
        val x1 = minOf(w - 1, floor((width - ox) / cw).toInt())
        val y1 = minOf(h - 1, floor((height - oy) / ch).toInt())
        val map = state.map
        val sheet = tileSheet.takeIf { tilesOn }
        for (y in y0..y1) for (x in x0..x1) {
            val i = N.MAP_CELL_INTS * (y * w + x)
            val tile = map[i + 2]
            if (sheet != null && tile >= 0) {
                tileDst.set(ox + x * cw, oy + y * ch, ox + (x + 1) * cw, oy + (y + 1) * ch)
                /* Tiles are transparent: the terrain goes beneath, the alert / glow marker on top. */
                val bg = map[i + 3]
                val flags = map[i + 4]
                val cellSheet = if (flags and N.TILE_RAGE != 0) rageSheet ?: sheet else sheet
                if (bg >= 0 && bg != tile) drawTile(canvas, cellSheet, bg)
                drawTile(canvas, cellSheet, tile)
                if (flags and N.TILE_ALERT != 0) drawTile(canvas, cellSheet, state.meta[N.META_TILE_ALERT])
                if (flags and N.TILE_GLOW != 0) drawTile(canvas, cellSheet, state.meta[N.META_TILE_GLOW])
            } else {
                val effect = (map[i + 1] ushr 16) and (N.CELL_ANIM or N.CELL_TRAIL or N.CELL_MARK)
                if (effect != 0) drawEffect(canvas, sheet, map[i + 3], ox + x * cw, oy + y * ch, cw, ch, effect, map[i + 1] and 0xFF)
                painter.drawCell(canvas, ox + x * cw, oy + y * ch, map[i], map[i + 1] and 0xFFFF)
            }
        }
        drawPath(canvas, ox, oy, cw, ch)
        /* The current target, between shots (aiming draws its own). */
        val tx = state.meta[N.META_TARGET_X]
        val ty = state.meta[N.META_TARGET_Y]
        if (tx >= 0 && aim == null) canvas.drawRect(ox + tx * cw, oy + ty * ch, ox + (tx + 1) * cw, oy + (ty + 1) * ch, targetPaint)
        flash?.let { (fx, fy) -> canvas.drawRect(ox + fx * cw, oy + fy * ch, ox + (fx + 1) * cw, oy + (fy + 1) * ch, flashPaint) }
        val (px, py) = player
        canvas.drawRect(ox + px * cw, oy + py * ch, ox + (px + 1) * cw, oy + (py + 1) * ch, playerPaint)
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** A spell, missile or hit mark in flight: the terrain beneath (tile mode), and a soft glow in the effect's colour behind its glyph. */
    private fun drawEffect(canvas: Canvas, sheet: Bitmap?, bgTile: Int, left: Float, top: Float, cw: Float, ch: Float, effect: Int, attr: Int) {
        if (sheet != null && bgTile >= 0) {
            tileDst.set(left, top, left + cw, top + ch)
            drawTile(canvas, sheet, bgTile)
        }
        if (effect and N.CELL_MARK != 0) return
        val head = effect and N.CELL_ANIM != 0
        glowPaint.color = (state.colour(attr % 32) and 0xFFFFFF) or ((if (head) 150 else 60) shl 24)
        canvas.drawCircle(left + cw / 2f, top + ch / 2f, minOf(cw, ch) * (if (head) 0.6f else 0.4f), glowPaint)
    }

    /** Draws tile [t] ((row << 8) | column) of [sheet] into [tileDst]. */
    private fun drawTile(canvas: Canvas, sheet: Bitmap, t: Int) {
        if (t < 0) return
        val col = t and 0xFF
        val row = t shr 8
        tileSrc.set(col * TILE_PX, row * TILE_PX, (col + 1) * TILE_PX, (row + 1) * TILE_PX)
        canvas.drawBitmap(sheet, tileSrc, tileDst, tilePaint)
    }

    private val aim get() = state.prompt as? GameState.Prompt.Aim
    private val direction get() = state.prompt as? GameState.Prompt.Direction

    /** Direction mode: outline all eight neighbours; fill the interesting ones.  The d-pad shows them instead when it is on. */
    private fun drawDirection(canvas: Canvas, d: GameState.Prompt.Direction, ox: Float, oy: Float, cw: Float, ch: Float) {
        if (padShown()) return
        val (px, py) = player
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0 && !d.allowHere) continue
            val x = px + dx
            val y = py + dy
            canvas.drawRect(ox + x * cw, oy + y * ch, ox + (x + 1) * cw, oy + (y + 1) * ch, targetPaint)
        }
        d.neighbours.forEach { n -> canvas.drawRect(ox + n.x * cw, oy + n.y * ch, ox + (n.x + 1) * cw, oy + (n.y + 1) * ch, pathPaint) }
    }

    /** Aim mode: ring every targetable monster; outline the current target. */
    private fun drawAim(canvas: Canvas, aim: GameState.Prompt.Aim, ox: Float, oy: Float, cw: Float, ch: Float) {
        aim.targets.forEach { t ->
            targetRingPaint.alpha = if (t.inRange) 255 else 90
            canvas.drawOval(ox + t.x * cw - 2f, oy + t.y * ch - 2f, ox + (t.x + 1) * cw + 2f, oy + (t.y + 1) * ch + 2f, targetRingPaint)
        }
        aim.current?.let { (x, y) -> canvas.drawRect(ox + x * cw, oy + y * ch, ox + (x + 1) * cw, oy + (y + 1) * ch, targetPaint) }
    }

    private fun drawPath(canvas: Canvas, ox: Float, oy: Float, cw: Float, ch: Float) {
        aim?.let { drawAim(canvas, it, ox, oy, cw, ch); return }
        direction?.let { drawDirection(canvas, it, ox, oy, cw, ch); return }
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
        if (aim != null) { Trace.log("map.tap", "kind" to "aim"); N.nativeAnswer(aimAt(g.first, g.second)); return }
        /* In a fight -more- comes often and the eyes are on the map: any tap continues. */
        if (!state.atPrompt && state.prompt == null && state.messageLine.contains("-more-")) { Trace.log("more.tap", "via" to "map"); router.special(Keys.ENTER); return }
        direction?.let { d ->
            Trace.log("map.tap", "kind" to "direction")
            val (px, py) = player
            val dx = g.first - px
            val dy = g.second - py
            if (abs(dx) <= 1 && abs(dy) <= 1 && (dx != 0 || dy != 0 || d.allowHere)) N.nativeAnswer(keypadDir(dx, dy))
            return
        }
        if (!state.atPrompt) return
        val (px, py) = player
        val adjacent = abs(g.first - px) <= 1 && abs(g.second - py) <= 1 && g != player
        when {
            adjacent -> { Trace.log("map.tap", "kind" to "step"); select(null); N.nativeRequestTravel(g.first, g.second) }
            g == selected -> { Trace.log("map.tap", "kind" to "go", "dist" to maxOf(abs(g.first - px), abs(g.second - py))); selected = null; N.nativeRequestTravel(g.first, g.second) }
            else -> { Trace.log("map.tap", "kind" to "preview", "dist" to maxOf(abs(g.first - px), abs(g.second - py))); select(g) }
        }
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!multiTouch) gridAt(e.x, e.y)?.let(::onTap)
            return true
        }

        /** Long press: describe what is there (monster recall, objects, terrain). */
        override fun onLongPress(e: MotionEvent) {
            if (multiTouch || aim != null || direction != null) return
            gridAt(e.x, e.y)?.let { (x, y) ->
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                Trace.log("map.hold", "kind" to "inspect")
                N.nativeRequestInspect(x, y)
            }
        }

        /** Pan mode: one finger drags the map, except while a swipe answers a prompt. */
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (multiTouch || !panning) return false
            if (!panned) Trace.log("map.swipe", "kind" to "pan")
            panned = true
            panBy(-distanceX, -distanceY)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            if (multiTouch || panning) return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            if (hypot(dx, dy) < 2f * cellW) return false
            val o = octant(dx, dy)
            val run = swipeMode() == Settings.SWIPE_RUN
            Trace.log("map.swipe", "kind" to if (answering) "answer" else if (state.atPrompt && state.prompt == null) (if (run) "run" else "step") else "key")
            when {
                answering -> N.nativeAnswer(OCTANT_TO_DIR[o])
                state.atPrompt && state.prompt == null -> N.nativeRequestMove(OCTANT_TO_DIR[o], run)
                else -> router.direction(o)
            }
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
            MotionEvent.ACTION_DOWN -> { multiTouch = false; panned = false }
            MotionEvent.ACTION_POINTER_DOWN -> { multiTouch = true; lastFocus = focus(e) }
            MotionEvent.ACTION_POINTER_UP -> lastFocus = focus(e, excluding = e.actionIndex)
            MotionEvent.ACTION_MOVE -> if (multiTouch && e.pointerCount >= 2) pan(e)
        }
        gestures.onTouchEvent(e)
        return true
    }

    /** Mean pointer position, optionally ignoring a pointer that is lifting. */
    private fun focus(e: MotionEvent, excluding: Int = -1): Pair<Float, Float> {
        val ids = (0 until e.pointerCount).filter { it != excluding }
        return ids.map { e.getX(it) }.average().toFloat() to ids.map { e.getY(it) }.average().toFloat()
    }

    private fun pan(e: MotionEvent) {
        val f = focus(e)
        panBy(f.first - lastFocus.first, f.second - lastFocus.second)
        lastFocus = f
    }

    /** Moves the map with the finger by (dx, dy) pixels; the camera stops following the player. */
    private fun panBy(dx: Float, dy: Float) {
        camAnimator?.cancel()
        followPlayer = false
        camX -= dx / cellW
        camY -= dy / cellH
        invalidate()
    }

    private val answering get() = aim != null || direction != null
    /** One finger pans: Pan mode, at the command prompt or a textui screen alike, but not while a swipe answers. */
    private val panning get() = swipeMode() == Settings.SWIPE_PAN && !answering
    private var panned = false

    private companion object {
        const val FLASH_MS = 180L
        const val DEFAULT_CELL_DP = 22f
        const val TILE_PX = 16
        const val MIN_CELL_DP = 8f
        const val MAX_CELL_DP = 64f
    }
}
