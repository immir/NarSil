package org.narsil.android

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Material's styles fix line height in sp (bodyLarge: 24sp), which every Text
 * inherits even at 12sp; make it proportional to the font size instead, and
 * drop Android's extra font padding above and below each line.
 */
internal fun compactTypography(): Typography = Typography().run {
    fun TextStyle.fit() = copy(lineHeight = 1.3.em, platformStyle = PlatformTextStyle(includeFontPadding = false),
                               lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
    Typography(displayLarge.fit(), displayMedium.fit(), displaySmall.fit(), headlineLarge.fit(), headlineMedium.fit(),
               headlineSmall.fit(), titleLarge.fit(), titleMedium.fit(), titleSmall.fit(), bodyLarge.fit(), bodyMedium.fit(),
               bodySmall.fit(), labelLarge.fit(), labelMedium.fit(), labelSmall.fit())
}
