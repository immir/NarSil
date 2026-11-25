package org.narsil.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The look of the app's own screens, in one place; ios/NarSil/Style.swift's Palette has the same names and values.
 * Gold means selected or active, blue ([Pressed]) means pressed, [Bad] means danger.
 */
object Ui {
    val Gold = Color(0xFFF3D27A)
    /** Text and icons on a gold fill. */
    val OnGold = Color(0xFF241A00)
    val Text = Color(0xFFE6E6EA)
    val Body = Color(0xFFD0D0D4)
    val Sub = Color(0xFF9A9AA0)
    val Verse = Color(0xFF8FC3FF)
    val Summary = Color(0xFF90CAF9)
    val Good = Color(0xFF81C784)
    val Bad = Color(0xFFE57373)
    val Warn = Color(0xFFFFB74D)
    /** Switched off, locked, out of reach. */
    val Dim = Color(0xFF5A5A60)

    /** Behind full screens and the splash, then the strips (messages, status, buttons), sheets, cards and raised buttons. */
    val Splash = Color(0xFF0E0E10)
    val Screen = Color(0xFF121214)
    val Chrome = Color(0xFF141414)
    val Sheet = Color(0xFF1C1C1E)
    val Card = Color(0xFF1E1E22)
    val Raised = Color(0xFF26262B)
    val Divider = Color(0xFF2C2C2E)
    val Pressed = Color(0xFF3A5F8A)
    /** A faint edge where one surface meets another. */
    val Hairline = Color(0x1AFFFFFF)

    val SheetTop = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val CardShape = RoundedCornerShape(10.dp)

    val edge = BorderStroke(1.dp, Hairline)

    /** Material's own colours (switches, sliders, chips, tonal buttons) follow the same palette instead of its purple. */
    fun colorScheme(): ColorScheme = darkColorScheme(
        primary = Gold, onPrimary = OnGold,
        primaryContainer = Gold, onPrimaryContainer = OnGold,
        secondary = Gold, onSecondary = OnGold,
        secondaryContainer = Color(0xFF2E2E34), onSecondaryContainer = Text,
        tertiary = Summary,
        background = Screen, onBackground = Text,
        surface = Sheet, onSurface = Text,
        surfaceVariant = Raised, onSurfaceVariant = Sub,
        outline = Sub, outlineVariant = Divider,
        error = Bad,
    )
}

/** A card: its surface, with the faint edge. */
fun Modifier.cardEdge(): Modifier = border(Ui.edge, Ui.CardShape)
