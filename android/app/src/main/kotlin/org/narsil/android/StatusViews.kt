package org.narsil.android

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* The status strip, message strip and their pieces, below and above the map. */

/**
 * The last few messages (older ones dimmer, in their game colours), plus the
 * textui's top line when it holds something else: a prompt, or -more- (tap = Enter).
 */
@Composable
internal fun MessageStrip(state: GameState, lines: Int, onTap: () -> Unit) {
    val recent = state.messages.takeLast(lines)
    val line0 = state.messageLine
    val more = line0.contains("-more-")
    /* The textui joins several messages on its top line; only show it when it is something else (a prompt). */
    val banner = line0.takeIf { it.isNotEmpty() && !more && state.messages.none { m -> line0.contains(m.text) } }
    Row(Modifier.fillMaxWidth().background(Color(0xFF141414)), verticalAlignment = Alignment.Top) {
    Column(Modifier.weight(1f).clickable(onClick = onTap).padding(horizontal = 10.dp, vertical = 4.dp)) {
        recent.forEachIndexed { i, m ->
            val alpha = 0.45f + 0.55f * (i + 1) / recent.size
            Text(Special.styled(m, if (m.count > 1) " (x${m.count})" else ""),
                 color = Color(state.colour(m.attr)).copy(alpha = alpha), fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2)
        }
        if (banner != null) Text(banner, color = Color(0xFFFFD54F), fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2)
        if (more) Text("▸ tap to continue", color = Color(0xFFFFD54F), fontSize = 13.sp, lineHeight = 16.sp)
        val resting = state.status.has(NarSilNative.CTX_RESTING)
        if (resting) Text("Resting…", color = Color(0xFF8AB4F8), fontSize = 13.sp, lineHeight = 16.sp)
        if (recent.isEmpty() && banner == null && !more && !resting) Text(" ", fontSize = 14.sp)
    }
        PanelButton()
    }
}

/** The depth, and in NarSil-Q (as in Sil-Q) the minimum depth the depth clock now allows going up to. */
private fun depthText(s: GameState.Status): String =
    if (NarSilNative.silq && s.minDepth > 0) "${s.depth * 50} ft · min ${s.minDepth * 50} ft" else "${s.depth * 50} ft"

/** Opens the character panel (pack, character, skills, …) at its last tab. */
@Composable
internal fun PanelButton() {
    Box(Modifier.padding(4.dp).size(40.dp).background(Color(0xFF26262B), RoundedCornerShape(10.dp))
            .semantics { contentDescription = "Character panel"; role = Role.Button }
            .clickable { Trace.log("panel.request", "via" to "button"); NarSilNative.nativeRequestAction(NarSilNative.ACT_PANEL) },
        contentAlignment = Alignment.Center) {
        PanelGlyph(PANEL_ICON_PATHS[ICON_PANEL], Color(0xFFF3D27A), Modifier.size(26.dp))
    }
}

@Composable
internal fun StatusStrip(state: GameState, stacked: Boolean, buzzOnLowHealth: Boolean, buzzInCombat: Boolean) {
    val s = state.status
    if (buzzInCombat) CombatBuzz(s.feelSeq, s.feelBits)
    LowHealthBuzz(s.lowHp && buzzOnLowHealth)
    if (buzzOnLowHealth) BigHitBuzz(s.hp, s.maxHp)
    val hpColour = if (s.lowHp) Color(0xFFFF3B30) else Color(0xFFD04040)
    Column(Modifier.fillMaxWidth().background(Color(0xFF141414)).clickable { Trace.log("panel.request", "via" to "status"); NarSilNative.nativeRequestAction(NarSilNative.ACT_CHARACTER) }
               .padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (stacked) {
            Meter("HP", s.hp, s.maxHp, hpColour, Modifier.fillMaxWidth(), alarm = s.lowHp)
            Meter("Voice", s.voice, s.maxVoice, Color(0xFF4080E0), Modifier.fillMaxWidth().padding(top = 4.dp))
            Text(depthText(s), color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Meter("HP", s.hp, s.maxHp, hpColour, Modifier.weight(1f), alarm = s.lowHp)
            Meter("Voice", s.voice, s.maxVoice, Color(0xFF4080E0), Modifier.weight(1f))
            Text(depthText(s), color = Color.White, fontSize = 13.sp)
        }
        StatusChips(state)
        s.foe?.let { FoeLine(it, state) }
    }
}

/** A short double buzz each time health falls below the hitpoint warning. */
@Composable
internal fun LowHealthBuzz(low: Boolean) {
    val context = LocalContext.current
    var was by remember { mutableStateOf(low) }
    LaunchedEffect(low) {
        if (low && !was) buzz(context)
        was = low
    }
}

/** One short pulse when a single turn costs a fifth or more of maximum health. */
@Composable
internal fun BigHitBuzz(hp: Int, maxHp: Int) {
    val context = LocalContext.current
    var was by remember { mutableStateOf(hp) }
    LaunchedEffect(hp) {
        if (maxHp > 0 && was - hp >= maxOf(1, maxHp / 5)) buzz(context, longArrayOf(0, 120))
        was = hp
    }
}

/** Feedback for the player's own attacks: a firm tick for a blow that lands, a stronger double pulse for a kill, a faint one for a miss. */
@Composable
internal fun CombatBuzz(seq: Int, bits: Int) {
    val context = LocalContext.current
    var seen by remember { mutableStateOf(seq) }
    LaunchedEffect(seq) {
        if (seq != seen) {
            when {
                bits and NarSilNative.FEEL_KILL != 0 -> pulse(context, longArrayOf(0, 40, 50, 90), intArrayOf(0, 200, 0, 255))
                bits and NarSilNative.FEEL_HIT != 0 -> pulse(context, longArrayOf(0, 35), intArrayOf(0, 200))
                bits and NarSilNative.FEEL_MISS != 0 -> pulse(context, longArrayOf(0, 15), intArrayOf(0, 70))
            }
        }
        seen = seq
    }
}

private fun pulse(context: Context, timings: LongArray, amplitudes: IntArray) {
    val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                   else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    if (vibrator?.hasVibrator() != true) return
    if (Build.VERSION.SDK_INT >= 26 && vibrator.hasAmplitudeControl()) vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    else if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
    else @Suppress("DEPRECATION") vibrator.vibrate(timings, -1)
}

private fun buzz(context: Context, pattern: LongArray = longArrayOf(0, 70, 90, 70)) {
    val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                   else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    if (vibrator?.hasVibrator() != true) return
    if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    else @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
}

/** Tracked monster: glyph, name, health, alertness/morale; tap to inspect. */
@Composable
internal fun FoeLine(f: GameState.Foe, state: GameState) {
    val cell = state.mapCell(f.x, f.y)
    val stance = NarSilNative.STANCE_NAMES.getOrElse(f.stance) { "" }
    val healthColour = when {
        f.healthPct >= 60 -> Color(0xFF66BB6A)
        f.healthPct >= 25 -> Color(0xFFFFCA28)
        else -> Color(0xFFEF5350)
    }
    Row(Modifier.fillMaxWidth().clickable { NarSilNative.nativeRequestInspect(f.x, f.y) }.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(cell?.let { String(Character.toChars(it.first)) } ?: "?", fontFamily = FontFamily.Monospace,
             color = Color(cell?.let { state.colour(it.second) } ?: 0xFFFFFFFF.toInt()), fontSize = 16.sp)
        Text(f.name, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
        LinearProgressIndicator(progress = f.healthPct / 100f, color = healthColour, trackColor = healthColour.copy(alpha = 0.25f),
                                modifier = Modifier.width(80.dp).height(6.dp))
        val morale = if (f.stance >= 4) " ${f.morale}" else ""
        Text("$stance$morale", color = Color(0xFF90CAF9), fontSize = 12.sp)
    }
}

@Composable
internal fun Meter(label: String, value: Int, max: Int, colour: Color, modifier: Modifier, alarm: Boolean = false) {
    Column(modifier) {
        Text("$label $value/$max" + if (alarm) "  ⚠" else "", color = if (alarm) colour else Color.White, fontSize = 12.sp,
             fontWeight = if (alarm) FontWeight.Bold else FontWeight.Normal)
        LinearProgressIndicator(
            progress = if (max > 0) value.toFloat() / max else 0f,
            color = colour,
            trackColor = colour.copy(alpha = 0.25f),
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
    }
}

@Composable
internal fun Centered(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Color.White)
        if (action != null) Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(action) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusChips(state: GameState) {
    val s = state.status
    val amber = Color(0xFFFFCA28)
    val red = Color(0xFFEF5350)
    val segments = state.statusSegments().filterNot { (text, _) -> text.matches(Regex("""\d+'?\s*(ft)?""")) }
    val light = segments.firstOrNull { it.first.startsWith("Light") }
    val others = segments.filter { it !== light }
    FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (s.newExp > 0) Chip("${s.newExp}", icon = ICON_EXP, colour = if (s.newExp >= 100) Color(0xFF90CAF9) else Color(0xFFB0B0B0)) { NarSilNative.nativeRequestAction(NarSilNative.ACT_SKILLS) }
        if (light != null || s.lightTurns >= 0) {
            val turns = s.lightTurns
            val colour = when { turns in 0..99 -> red; turns in 0..299 -> amber; else -> light?.second?.let { Color(it) } ?: Color(0xFFB0B0B0) }
            Chip(listOfNotNull(light?.first, if (turns >= 0) "${turns}t" else null).joinToString(" · "), colour, icon = ICON_LIGHT)
        }
        others.forEach { (text, colour) -> Chip(text, Color(colour)) }
        if (s.threats > 0) Chip("${s.threats} · ${s.threatNearest} away", if (s.threatsAlert > 0) red else Color(0xFFB0B0B0), icon = ICON_THREATS) { NarSilNative.nativeRequestLookMonsters() }
    }
}

@Composable
private fun Chip(text: String, colour: Color, icon: Int? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.background(colour.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (icon != null) PanelGlyph(PANEL_ICON_PATHS[icon], colour, Modifier.size(12.dp), stroke = 3f)
        Text(text, color = colour, fontSize = 12.sp, maxLines = 1)
    }
}
