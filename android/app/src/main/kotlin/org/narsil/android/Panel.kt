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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
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
private val PANEL_BG = Color(0xFF1C1C1E)

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
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), color = PANEL_BG,
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
fun PanelHost(tab: Int, onAnswer: (Int) -> Unit, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = if (maxWidth > maxHeight) maxWidth * 0.58f else maxWidth * 0.94f
        Box(Modifier.fillMaxSize().background(Color(0x77000000))
                .clickable(remember { MutableInteractionSource() }, indication = null) { onAnswer(N.PANEL_CLOSE) })
            Surface(color = PANEL_BG, shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    modifier = Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, indication = null) {}) {
                Column {
                    Row(Modifier.fillMaxWidth().swipeRightToClose { onAnswer(N.PANEL_CLOSE) }, verticalAlignment = Alignment.CenterVertically) {
                        ScrollableTabRow(selectedTabIndex = tab.coerceIn(0, TAB_TITLES.lastIndex), containerColor = PANEL_BG,
                                         edgePadding = 4.dp, modifier = Modifier.weight(1f)) {
                            TAB_TITLES.forEachIndexed { i, t ->
                                Tab(selected = i == tab, onClick = { if (i != tab) onAnswer(N.PANEL_TAB + i) }, text = { Text(t, fontSize = 14.sp) })
                            }
                        }
                        TextButton(onClick = { onAnswer(N.PANEL_CLOSE) }) { Text("✕", fontSize = 18.sp) }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().swipeBetweenTabs(tab, onAnswer)) {
                        CompositionLocalProvider(LocalInPanel provides true) { content() }
                    }
                }
            }
    }
}

/**
 * A sideways swipe across a tab's screen: left for the next tab, right for the
 * previous (right on the first tab closes the panel).  Controls that scroll
 * sideways themselves (skill tabs, sliders) take their own swipes first.
 */
private fun Modifier.swipeBetweenTabs(tab: Int, onAnswer: (Int) -> Unit) = pointerInput(tab) {
    var dx = 0f
    detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
        when {
            dx < -72.dp.toPx() && tab + 1 < N.TAB_COUNT -> onAnswer(N.PANEL_TAB + tab + 1)
            dx > 72.dp.toPx() -> onAnswer(if (tab > 0) N.PANEL_TAB + tab - 1 else N.PANEL_CLOSE)
        }
    }) { _, d -> dx += d }
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
                    onDragEnd = { if (-dx > threshold) N.nativeRequestAction(N.ACT_PANEL) }) { _, d -> dx += d }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(Modifier.size(width = 4.dp, height = 44.dp).background(Color(0x55FFFFFF), RoundedCornerShape(2.dp)))
    }
}
