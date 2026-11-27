package org.narsil.android

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.narsil.android.NarSilNative as N

/**
 * Where each list was scrolled, by screen ("knowledge/creatures", "options/main", …),
 * so a tab or view comes back where it was left (UI thread only).
 */
private object ScrollMemory {
    val lists = HashMap<String, Pair<Int, Int>>()
    val scrolls = HashMap<String, Int>()
}

/** A LazyListState that starts where the list called [key] was left. */
@Composable
fun rememberListPlace(key: String): LazyListState {
    val st = remember(key) { ScrollMemory.lists[key]?.let { (i, o) -> LazyListState(i, o) } ?: LazyListState() }
    DisposableEffect(key) { onDispose { ScrollMemory.lists[key] = st.firstVisibleItemIndex to st.firstVisibleItemScrollOffset } }
    return st
}

/** A ScrollState that starts where the column called [key] was left. */
@Composable
fun rememberScrollPlace(key: String): ScrollState {
    val st = remember(key) { ScrollState(ScrollMemory.scrolls[key] ?: 0) }
    DisposableEffect(key) { onDispose { ScrollMemory.scrolls[key] = st.value } }
    return st
}

/** True inside the character panel: sheets drop their own frame (scrim, rounded sheet, Close). */
val LocalInPanel = staticCompositionLocalOf { false }

private val TAB_TITLES = listOf("Pack", "Character", "Skills", "Abilities", "Knowledge", "Smithing", "Options")

/** The prompts that are a tab's own screen (anything else, e.g. a yes/no, is asked over it). */
fun GameState.Prompt.isPanelContent() = when (this) {
    is GameState.Prompt.Pack, is GameState.Prompt.Character, is GameState.Prompt.Skills, is GameState.Prompt.Abilities,
    is GameState.Prompt.Knowledge, is GameState.Prompt.Smith, is GameState.Prompt.Options -> true
    else -> false
}

/**
 * A bottom sheet over a scrim, or, inside the panel, just the panel's content
 * area.  [heightFraction] or [maxHeight] limit the sheet outside the panel.
 */
@Composable
fun PanelAwareSheet(onDismiss: (() -> Unit)?, heightFraction: Float? = null, maxHeight: Dp? = null, content: @Composable () -> Unit) {
    if (LocalInPanel.current) { Box(Modifier.fillMaxSize()) { content() }; return }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .then(if (onDismiss != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                  else Modifier),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = Ui.SheetTop, border = Ui.edge, color = Ui.Sheet,
            modifier = Modifier.fillMaxWidth()
                .then(heightFraction?.let { Modifier.fillMaxHeight(it) } ?: maxHeight?.let { Modifier.heightIn(max = it) } ?: Modifier)
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) { content() }
    }
}

/**
 * The character panel: tabs over the pack, character, skills, abilities,
 * knowledge, smithing and options, sliding in from the right.  The game owns
 * the tabs (native_panel()); a tab tap answers PANEL_TAB + tab.  [content]
 * draws the open tab's screen.
 */
@Composable
fun PanelHost(gameTab: Int, tabStyle: Int, tabsOnTop: Boolean, onAnswer: (Int) -> Unit, content: @Composable () -> Unit) {
    /* The tab asked for shows at once; the game's answer (its new screen) follows. */
    var asked by remember { mutableStateOf(-1) }
    LaunchedEffect(gameTab) { asked = -1 }
    /* An answer sent in the instant between two screens is dropped: fall back to the game's tab. */
    LaunchedEffect(asked) { if (asked >= 0) { delay(600); asked = -1 } }
    val tab = if (asked >= 0) asked else gameTab
    val answer: (Int) -> Unit = { a ->
        if (a >= N.PANEL_TAB && a < N.PANEL_CLOSE) asked = a - N.PANEL_TAB
        onAnswer(a)
    }
    /* Landscape: the tabs stay in the column down the right edge, where they were over the map. */
    val sideTabs = tabsOnTop && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize()) {
    /* Tabs along the top: the same strip as over the map, now showing the open tab; its tab again closes. */
    if (tabsOnTop && !sideTabs) TabsBar(tab, tabStyle) { i -> answer(if (i == tab) N.PANEL_CLOSE else N.PANEL_TAB + i) }
    Row(Modifier.weight(1f).fillMaxWidth()) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
        /* A wide window (tablet, foldable): a side column of a readable width, the map still showing beside it. */
        val wide = maxWidth >= 840.dp
        /* With the tabs along the top the panel fills a portrait screen; in landscape it keeps a share of the width. */
        val width = if (wide) minOf(maxWidth * 0.4f, 560.dp) else if (maxWidth > maxHeight) maxWidth * 0.58f else if (tabsOnTop) maxWidth else maxWidth * 0.94f
        Box(Modifier.fillMaxSize().background(Color(if (wide) 0x33000000 else 0x77000000))
                .clickable(remember { MutableInteractionSource() }, indication = null) { answer(N.PANEL_CLOSE) })
            Surface(color = Ui.Sheet, border = Ui.edge, shape = RoundedCornerShape(topStart = if (tabsOnTop) 0.dp else 16.dp, bottomStart = 16.dp),
                    modifier = Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, indication = null) {}) {
                Column {
                    if (!tabsOnTop) Row(Modifier.fillMaxWidth().swipeRightToClose { answer(N.PANEL_CLOSE) }, verticalAlignment = Alignment.CenterVertically) {
                        PanelTabs(tab, tabStyle, Modifier.weight(1f)) { i -> if (i != tab) answer(N.PANEL_TAB + i) }
                        TextButton(onClick = { answer(N.PANEL_CLOSE) }) { Text("✕", fontSize = 18.sp) }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().swipeBetweenTabs(tab, answer)) {
                        CompositionLocalProvider(LocalInPanel provides true) { content() }
                    }
                }
            }
    }
    if (sideTabs) TabsRail(tab) { i -> answer(if (i == tab) N.PANEL_CLOSE else N.PANEL_TAB + i) }
    }
    }
}

/** The seven tabs in a row, [tab] selected: icons (with names, or not) fit the width; names alone scroll. */
@Composable
private fun PanelTabs(tab: Int, tabStyle: Int, modifier: Modifier, onTab: (Int) -> Unit) {
    if (tabStyle == Settings.TABS_NAMES) {
        ScrollableTabRow(selectedTabIndex = tab.coerceIn(0, TAB_TITLES.lastIndex), containerColor = Ui.Sheet,
                         edgePadding = 4.dp, modifier = modifier) {
            TAB_TITLES.forEachIndexed { i, t -> Tab(selected = i == tab, onClick = { onTab(i) }, text = { Text(t, fontSize = 14.sp) }) }
        }
    } else {
        /* Icons fit the panel's width, so there is nothing to scroll. */
        Row(modifier) {
            TAB_TITLES.forEachIndexed { i, t ->
                IconTab(PANEL_ICON_PATHS[i], t, showName = tabStyle == Settings.TABS_ICONS_AND_NAMES, selected = i == tab,
                        modifier = Modifier.weight(1f)) { onTab(i) }
            }
        }
    }
}

private val TABS_BAR_HEIGHT = 56.dp  // plus the hairline under it

/**
 * Settings → "Panel tabs along the top": the tabs as a strip across the top of the screen, drawn the same over the map
 * and over the open panel (its tab selected), so it does not move.  A tab opens its screen; the open tab closes it.
 */
@Composable
fun TabsBar(selected: Int, tabStyle: Int, onTab: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ui.Sheet)) {
        Row(Modifier.fillMaxWidth().height(TABS_BAR_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            PanelTabs(selected, tabStyle, Modifier.weight(1f), onTab)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.Divider))
    }
}

/** Landscape: the seven tabs as a column of icons down the right edge, [selected] in gold. */
@Composable
fun TabsRail(selected: Int, onTab: (Int) -> Unit) {
    Row(Modifier.width(48.dp).fillMaxHeight().background(Ui.Sheet)) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(Ui.Divider))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            TAB_TITLES.forEachIndexed { i, t ->
                Box(Modifier.weight(1f).fillMaxWidth().clickable { onTab(i) }, contentAlignment = Alignment.Center) {
                    PanelGlyph(PANEL_ICON_PATHS[i], if (i == selected) Ui.Gold else Ui.Sub, Modifier.size(24.dp).semantics { contentDescription = t })
                }
            }
        }
    }
}

/** What opens each tab, by tab index: the same actions as the pack, character and the rest have elsewhere. */
val TAB_ACTIONS = intArrayOf(N.ACT_INVENTORY, N.ACT_CHARACTER, N.ACT_SKILLS, N.ACT_ABILITIES, N.ACT_KNOWLEDGE, N.ACT_SMITH, N.ACT_OPTIONS)

/**
 * A sideways swipe across a tab's screen: left for the next tab, right for the
 * previous (right on the first tab closes the panel).  Controls that scroll
 * sideways themselves (skill tabs, sliders) take their own swipes first.
 */
private fun Modifier.swipeBetweenTabs(tab: Int, onAnswer: (Int) -> Unit) = pointerInput(tab) {
    var dx = 0f
    var done = false
    val far = 56.dp.toPx()
    /* Acts as soon as the swipe is far enough, not when the finger lifts. */
    detectHorizontalDragGestures(onDragStart = { dx = 0f; done = false }) { _, d ->
        dx += d
        if (done) return@detectHorizontalDragGestures
        when {
            dx < -far && tab + 1 < N.TAB_COUNT -> { done = true; onAnswer(N.PANEL_TAB + tab + 1) }
            dx > far -> { done = true; onAnswer(if (tab > 0) N.PANEL_TAB + tab - 1 else N.PANEL_CLOSE) }
        }
    }
}

/** One panel tab as an icon with its name under it; no padding, so seven fit across the panel. */
@Composable
private fun IconTab(icon: String, name: String, showName: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (selected) Ui.Gold else Ui.Sub
    Column(modifier.clickable(onClick = onClick).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PanelGlyph(icon, tint, Modifier.size(24.dp).semantics { contentDescription = name })
        if (showName) Text(name, color = tint, fontSize = with(LocalDensity.current) { 8.dp.toSp() }, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 2.dp))
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(2.dp).background(if (selected) tint else Color.Transparent))
    }
}

/** A swipe to the right over this element calls [onClose] (the tab strip: lists below scroll vertically). */
private fun Modifier.swipeRightToClose(onClose: () -> Unit) = pointerInput(Unit) {
    var dx = 0f
    detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx > 80.dp.toPx()) onClose() }) { _, d -> dx += d }
}

/**
 * Settings → "Swipe from the right edge opens the panel": a strip along the
 * middle of the right edge, claimed from the system back gesture, with a faint
 * handle.  A swipe left from it opens the panel at its last tab.
 */
@Composable
fun EdgeSwipeHandle(modifier: Modifier = Modifier) {
    var dx by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    Box(
        modifier.width(24.dp).fillMaxHeight(0.4f).systemGestureExclusion()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { dx = 0f },
                    onDragEnd = { if (-dx > threshold) { Trace.log("panel.request", "via" to "edge"); N.nativeRequestAction(N.ACT_PANEL) } }) { _, d -> dx += d }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(Modifier.size(width = 4.dp, height = 44.dp).background(Color(0x55FFFFFF), RoundedCornerShape(2.dp)))
    }
}

/** One of the generated icons (see PanelIcons.kt), stroked in [tint]. */
@Composable
internal fun PanelGlyph(icon: String, tint: Color, modifier: Modifier, stroke: Float = 1.8f) {
    val path = remember(icon) { PathParser().parsePathString(icon).toPath() }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) { drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}
