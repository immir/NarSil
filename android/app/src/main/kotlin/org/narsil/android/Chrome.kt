package org.narsil.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The two ways off a screen: Back goes up one level (a chevron), Close leaves the sheet or question (a ✕). */
enum class NavKind { Back, Close }

/**
 * A 48dp touch target with the icon drawn in the panel icons' stroke.  Back is always at the start of a header, Close
 * always at its end; the same size and look everywhere (ios/NarSil/Chrome.swift mirrors this).
 */
@Composable
fun NavIcon(kind: NavKind, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clickable(onClick = onClick)
            .semantics { contentDescription = if (kind == NavKind.Back) "Back" else "Close"; role = Role.Button },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            val s = size.width / 24f
            val stroke = 2f * s
            val ink = Ui.Sub
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(ink, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), strokeWidth = stroke, cap = StrokeCap.Round)
            when (kind) {
                NavKind.Back -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
                NavKind.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            }
        }
    }
}

/**
 * The title row of a screen or sheet: Back at the start when it is a level down, Close at the end when it can be left
 * (inside the panel its tabs do that), and whatever else belongs beside the title in between.
 */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, onClose: (() -> Unit)? = null,
                 subtitle: String? = null, subtitleColour: Color = Ui.Sub, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) NavIcon(NavKind.Back, onBack) else Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ui.Gold, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = subtitleColour, fontSize = 13.sp)
        }
        trailing()
        if (onClose != null) NavIcon(NavKind.Close, onClose) else Spacer(Modifier.width(8.dp))
    }
}
